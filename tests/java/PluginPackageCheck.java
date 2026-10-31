import org.telegram.messenger.plugins.packages.PluginPackageValidator;

import java.io.File;
import java.io.IOException;

public final class PluginPackageCheck {
    public static void main(String[] args) throws Exception {
        try {
            PluginPackageValidator.Result result =
                    PluginPackageValidator.inspect(
                            new File(args[0]),
                            new File(args[1]),
                            new FakePluginPythonRiskScanner());
            System.out.println(result.manifest.id);
            System.out.println(result.sha256);
            System.out.println(result.fileCount);
            System.out.println(result.unpackedBytes);
            System.out.println(result.riskReport.analyzerVersion);
            System.out.println(result.riskReport.hostFingerprint);
            System.out.println("signature:" + result.signatureVerification.status);
            System.out.println("rotation:" + (result.rotationProof == null
                    ? "none"
                    : result.rotationProof.fromGeneration + "->" + result.rotationProof.toGeneration));
            System.out.println("scanner-calls:" + FakePluginPythonRiskScanner.calls);
            System.out.println(
                    "decision:"
                            + org.telegram.messenger.plugins.risk.PluginRiskPolicy
                                    .evaluateInstall(result.riskReport)
                                    .decision);

            for (org.telegram.messenger.plugins.risk.PluginRiskReport.ModuleDigest module
                    : result.riskReport.modules) {
                System.out.println("module:" + module.role + ":" + module.sha256);
            }

            for (org.telegram.messenger.plugins.risk.PluginRiskEvidence evidence
                    : result.riskReport.evidence) {
                System.out.println("evidence:" + evidence.id + ":" + evidence.detail);
            }

            for (String limitation : result.riskReport.limitations) {
                System.out.println("limitation:" + limitation);
            }
        } catch (IOException expected) {
            System.err.println(expected.getMessage());
            System.exit(2);
        }
    }
}
