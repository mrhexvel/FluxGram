package org.telegram.messenger.plugins;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import org.telegram.messenger.plugins.risk.PluginPythonRiskScanner;
import org.telegram.messenger.plugins.risk.PluginPythonRiskResponseDecoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ChaquopyPluginPythonRiskScanner implements PluginPythonRiskScanner {
    @Override
    public Result scan(List<Source> sources) throws IOException {
        if (sources == null || sources.size() > MAX_SOURCES) {
            throw new IOException("Invalid Python analysis input");
        }

        ArrayList<Source> checkedSources = new ArrayList<>(sources.size());
        int total = 0;

        for (Source source : sources) {
            if (source == null
                    || source.size() > MAX_SOURCE_BYTES
                    || (total += source.size()) > MAX_TOTAL_SOURCE_BYTES) {
                throw new IOException("Invalid Python analysis input");
            }

            checkedSources.add(source);
        }

        try {
            Python python = PythonRuntime.prepare();
            PyObject builtins = python.getBuiltins();
            PyObject records = builtins.callAttr("list");

            for (Source source : checkedSources) {
                PyObject record = builtins.callAttr("list");
                record.callAttr("append", source.path);
                record.callAttr("append", builtins.callAttr("bytes", source.bytes()));
                records.callAttr("append", record);
            }

            PyObject module = python.getModule("fluxgram_runtime._risk_analysis");
            String encoded = module.callAttr("analyze", records).toString();
            return PluginPythonRiskResponseDecoder.decode(encoded);
        } catch (IOException error) {
            throw error;
        } catch (Exception | LinkageError error) {
            throw new IOException("Python static analysis failed", error);
        }
    }

}
