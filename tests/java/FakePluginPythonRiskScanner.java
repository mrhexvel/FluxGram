import org.telegram.messenger.plugins.risk.PluginPythonRiskScanner;
import org.telegram.messenger.plugins.risk.PluginRiskEvidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FakePluginPythonRiskScanner implements PluginPythonRiskScanner {
    public static int calls;

    @Override
    public Result scan(List<Source> sources) throws IOException {
        calls++;
        ArrayList<Finding> findings = new ArrayList<>();
        ArrayList<String> limitations = new ArrayList<>();

        for (Source source : sources) {
            String text = new String(source.bytes(), StandardCharsets.UTF_8);

            if (text.contains("FAKE_ERROR")) {
                throw new IOException("Fake scanner failure");
            }
            if (text.contains("FAKE_PROTOCOL")) {
                throw new IllegalArgumentException("Fake scanner protocol failure");
            }
            if (text.contains("FAKE_BLOCK")) {
                limitations.add("analysis.unsupported_python");
            }
            if (text.contains("FAKE_NETWORK")) {
                findings.add(new Finding(
                        "network.raw",
                        "network",
                        source.path,
                        PluginRiskEvidence.Confidence.EXACT));
            }
            if (text.contains("FAKE_SIDE_EFFECT")) {
                findings.add(new Finding(
                        "python.top_level_side_effect",
                        null,
                        source.path,
                        PluginRiskEvidence.Confidence.EXACT));
            }
        }

        return new Result(findings, limitations);
    }
}
