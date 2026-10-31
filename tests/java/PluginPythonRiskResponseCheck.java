import org.telegram.messenger.plugins.risk.PluginPythonRiskResponseDecoder;
import org.telegram.messenger.plugins.risk.PluginPythonRiskScanner;

import java.io.IOException;

public final class PluginPythonRiskResponseCheck {
    public static void main(String[] args) {
        try {
            PluginPythonRiskScanner.Result result =
                    PluginPythonRiskResponseDecoder.decode(args[0]);
            System.out.println(result.findings.size() + ":" + result.limitations.size());
        } catch (IOException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }
}
