import org.telegram.messenger.plugins.risk.PluginRiskCatalog;
import org.telegram.messenger.plugins.risk.PluginRiskDiff;
import org.telegram.messenger.plugins.risk.PluginRiskEvidence;
import org.telegram.messenger.plugins.risk.PluginRiskPolicy;
import org.telegram.messenger.plugins.risk.PluginRiskReport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PluginRiskReportCheck {
    private static final PluginRiskEvidence FULL_ACCESS = evidence(
            "access.full_process.dex",
            PluginRiskEvidence.Confidence.EXACT);
    private static final PluginRiskEvidence NETWORK = evidence(
            "network.raw",
            PluginRiskEvidence.Confidence.EXACT);
    private static final PluginRiskEvidence BLOCKED = evidence(
            "block.archive.undeclared_executable",
            PluginRiskEvidence.Confidence.EXACT);

    public static void main(String[] args) throws Exception {
        catalog();
        immutableReport();
        serialization();
        malformedReports();
        policy();
        updateDiff();
    }

    private static void catalog() {
        require(PluginRiskCatalog.resolve("network.raw").known);
        require(PluginRiskCatalog.resolve("network.raw").severity
                == PluginRiskCatalog.Severity.HIGH);
        require(PluginRiskCatalog.resolve("future.risk").disposition
                == PluginRiskCatalog.Disposition.CONFIRM);
        require(!PluginRiskCatalog.resolve("future.risk").known);
        require(PluginRiskCatalog.resolve("block.future_rule").disposition
                == PluginRiskCatalog.Disposition.BLOCK);
        require(PluginRiskCatalog.descriptors().size() >= 40);

        expectInvalid(() -> PluginRiskCatalog.resolve("Invalid"));
        expectUnsupported(() -> PluginRiskCatalog.descriptors().clear());
    }

    private static void immutableReport() {
        ArrayList<PluginRiskReport.ModuleDigest> modules = new ArrayList<>();
        modules.add(new PluginRiskReport.ModuleDigest("python", digest('b')));
        modules.add(new PluginRiskReport.ModuleDigest("java", digest('c')));
        ArrayList<PluginRiskEvidence> evidence = new ArrayList<>();
        evidence.add(NETWORK);
        evidence.add(FULL_ACCESS);
        evidence.add(evidence("network.raw", PluginRiskEvidence.Confidence.UNKNOWN));
        ArrayList<String> limitations = new ArrayList<>(Arrays.asList(
                "analysis.dynamic_target",
                "analysis.dynamic_target"));

        PluginRiskReport report = report(digest('a'), modules, evidence, limitations);
        modules.clear();
        evidence.clear();
        limitations.clear();

        require(report.modules.size() == 2);
        require(report.modules.get(0).role.equals("java"));
        require(report.evidence.size() == 2);
        require(report.evidence.get(1).confidence == PluginRiskEvidence.Confidence.UNKNOWN);
        require(report.limitations.equals(Collections.singletonList("analysis.dynamic_target")));
        require(report.hasEvidence("network.raw"));
        require(report.maximumSeverity() == PluginRiskCatalog.Severity.HIGH);

        expectUnsupported(() -> report.modules.clear());
        expectUnsupported(() -> report.evidence.clear());
        expectUnsupported(() -> report.limitations.clear());
        expectInvalid(() -> report(
                digest('a'),
                Arrays.asList(
                        new PluginRiskReport.ModuleDigest("java", digest('b')),
                        new PluginRiskReport.ModuleDigest("java", digest('c'))),
                Collections.emptyList(),
                Collections.emptyList()));
    }

    private static void serialization() throws Exception {
        PluginRiskEvidence future = new PluginRiskEvidence(
                "future.capability",
                "example.org",
                PluginRiskEvidence.Source.DEX,
                PluginRiskEvidence.Confidence.HEURISTIC);
        PluginRiskReport original = report(
                digest('d'),
                Collections.singletonList(
                        new PluginRiskReport.ModuleDigest("java", digest('e'))),
                Arrays.asList(FULL_ACCESS, future),
                Collections.singletonList("analysis.dynamic_target"));
        PluginRiskReport decoded = PluginRiskReport.decode(original.encode());

        require(decoded.archiveSha256.equals(original.archiveSha256));
        require(decoded.pluginId.equals(original.pluginId));
        require(decoded.pluginVersion.equals(original.pluginVersion));
        require(decoded.packageSchemaVersion == original.packageSchemaVersion);
        require(decoded.analyzerVersion == original.analyzerVersion);
        require(decoded.hostFingerprint.equals(original.hostFingerprint));
        require(decoded.analyzedAtMillis == original.analyzedAtMillis);
        require(decoded.modules.size() == 1);
        require(decoded.evidence.equals(original.evidence));
        require(decoded.limitations.equals(original.limitations));
        require(!PluginRiskCatalog.resolve("future.capability").known);

        PluginRiskReport reordered = report(
                digest('d'),
                Collections.singletonList(
                        new PluginRiskReport.ModuleDigest("java", digest('e'))),
                Arrays.asList(future, FULL_ACCESS),
                Collections.singletonList("analysis.dynamic_target"));
        require(Arrays.equals(original.encode(), reordered.encode()));
    }

    private static void malformedReports() throws Exception {
        byte[] valid = report(
                digest('f'),
                Collections.emptyList(),
                Collections.singletonList(FULL_ACCESS),
                Collections.emptyList()).encode();
        byte[] unsupported = valid.clone();
        unsupported[7] = 2;
        expectIo(() -> PluginRiskReport.decode(unsupported));

        byte[] previousVersion = valid.clone();
        previousVersion[7] = 0;
        expectIo(() -> PluginRiskReport.decode(previousVersion));

        byte[] invalidUtf8 = valid.clone();
        invalidUtf8[80] = (byte) 0xed;
        invalidUtf8[81] = (byte) 0xa0;
        invalidUtf8[82] = (byte) 0x80;
        expectIo(() -> PluginRiskReport.decode(invalidUtf8));
        expectIo(() -> PluginRiskReport.decode(Arrays.copyOf(valid, valid.length - 1)));

        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        trailing[trailing.length - 1] = 1;
        expectIo(() -> PluginRiskReport.decode(trailing));
        expectIo(() -> PluginRiskReport.decode(new byte[64 * 1024 + 1]));
        expectIo(() -> PluginRiskReport.decode(null));

        expectInvalid(() -> report(
                "bad",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
        expectInvalid(() -> new PluginRiskEvidence(
                "Bad ID",
                null,
                PluginRiskEvidence.Source.DEX,
                PluginRiskEvidence.Confidence.EXACT));
        expectInvalid(() -> new PluginRiskEvidence(
                "network.raw",
                "\ud800",
                PluginRiskEvidence.Source.DEX,
                PluginRiskEvidence.Confidence.EXACT));
        expectInvalid(() -> new PluginRiskReport(
                digest('a'),
                "example.plugin",
                "1.0.0",
                5,
                1,
                null,
                1,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
        expectInvalid(() -> new PluginRiskReport(
                digest('a'),
                null,
                "1.0.0",
                5,
                1,
                digest('0'),
                1,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
        expectInvalid(() -> new PluginRiskReport(
                digest('a'),
                "example.plugin",
                null,
                5,
                1,
                digest('0'),
                1,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()));
    }

    private static void policy() {
        PluginRiskReport ordinary = report(
                digest('1'),
                Collections.emptyList(),
                Collections.singletonList(FULL_ACCESS),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateInstall(ordinary).decision
                == PluginRiskPolicy.Decision.ALLOW);

        PluginRiskReport network = report(
                digest('2'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateInstall(network).decision
                == PluginRiskPolicy.Decision.CONFIRM);
        require(PluginRiskPolicy.evaluateInstall(network).maximumSeverity
                == PluginRiskCatalog.Severity.HIGH);
        require(PluginRiskPolicy.evaluateInstall(network).reasons
                .equals(Collections.singletonList("network.raw")));

        PluginRiskReport unknown = report(
                digest('3'),
                Collections.emptyList(),
                Collections.singletonList(evidence(
                        "future.capability",
                        PluginRiskEvidence.Confidence.EXACT)),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateInstall(unknown).decision
                == PluginRiskPolicy.Decision.CONFIRM);

        PluginRiskReport incomplete = report(
                digest('4'),
                Collections.emptyList(),
                Collections.singletonList(FULL_ACCESS),
                Collections.singletonList("analysis.incomplete"));
        require(PluginRiskPolicy.evaluateInstall(incomplete).decision
                == PluginRiskPolicy.Decision.BLOCK);
        require(PluginRiskPolicy.evaluateInstall(incomplete).maximumSeverity
                == PluginRiskCatalog.Severity.CRITICAL);

        PluginRiskReport futureBlock = report(
                digest('c'),
                Collections.emptyList(),
                Collections.singletonList(evidence(
                        "block.future_rule",
                        PluginRiskEvidence.Confidence.EXACT)),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateInstall(futureBlock).decision
                == PluginRiskPolicy.Decision.BLOCK);

        PluginRiskReport blocked = report(
                digest('5'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, BLOCKED),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateInstall(blocked).decision
                == PluginRiskPolicy.Decision.BLOCK);
    }

    private static void updateDiff() {
        PluginRiskReport previous = report(
                digest('6'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskReport sameRisk = report(
                digest('7'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskDiff sameRiskDiff = PluginRiskDiff.between(previous, sameRisk);

        require(!sameRiskDiff.isEmpty());
        require(sameRiskDiff.addedEvidence.isEmpty());
        require(PluginRiskPolicy.evaluateUpdate(previous, sameRisk).decision
                == PluginRiskPolicy.Decision.ALLOW);

        PluginRiskEvidence shell = evidence(
                "process.shell_execution",
                PluginRiskEvidence.Confidence.EXACT);
        PluginRiskReport newRisk = report(
                digest('8'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, NETWORK, shell),
                Collections.emptyList());
        PluginRiskDiff newRiskDiff = PluginRiskDiff.between(previous, newRisk);

        require(newRiskDiff.addedEvidence.equals(Collections.singletonList(shell)));
        require(PluginRiskPolicy.evaluateUpdate(previous, newRisk).decision
                == PluginRiskPolicy.Decision.CONFIRM);

        PluginRiskReport blocked = report(
                digest('9'),
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, BLOCKED),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateUpdate(previous, blocked).decision
                == PluginRiskPolicy.Decision.BLOCK);

        PluginRiskReport uncertain = report(
                digest('a'),
                Collections.emptyList(),
                Arrays.asList(
                        FULL_ACCESS,
                        evidence("network.raw", PluginRiskEvidence.Confidence.UNKNOWN)),
                Collections.emptyList());
        require(PluginRiskPolicy.evaluateUpdate(previous, uncertain).decision
                == PluginRiskPolicy.Decision.CONFIRM);

        PluginRiskReport moduleChanged = report(
                digest('b'),
                Collections.singletonList(
                        new PluginRiskReport.ModuleDigest("java", digest('c'))),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskDiff moduleDiff = PluginRiskDiff.between(previous, moduleChanged);
        require(moduleDiff.addedModules.size() == 1);
        require(PluginRiskPolicy.evaluateUpdate(previous, moduleChanged).reasons
                .contains("update.module_composition_changed"));
        require(PluginRiskPolicy.evaluateUpdate(previous, moduleChanged).decision
                == PluginRiskPolicy.Decision.CONFIRM);

        PluginRiskReport previousModule = report(
                digest('c'),
                Collections.singletonList(
                        new PluginRiskReport.ModuleDigest("java", digest('d'))),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskReport changedDigest = report(
                digest('d'),
                Collections.singletonList(
                        new PluginRiskReport.ModuleDigest("java", digest('e'))),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskDiff digestDiff = PluginRiskDiff.between(previousModule, changedDigest);
        require(digestDiff.addedModules.isEmpty());
        require(digestDiff.removedModules.isEmpty());
        require(digestDiff.changedModuleDigests.equals(Collections.singletonList("java")));
        require(!PluginRiskPolicy.evaluateUpdate(previousModule, changedDigest).reasons
                .contains("update.module_composition_changed"));
        require(PluginRiskPolicy.evaluateUpdate(previousModule, changedDigest).decision
                == PluginRiskPolicy.Decision.ALLOW);

        PluginRiskReport contextChanged = new PluginRiskReport(
                digest('c'),
                "example.plugin",
                "2.0.0",
                6,
                2,
                digest('d'),
                1234,
                Collections.emptyList(),
                Arrays.asList(FULL_ACCESS, NETWORK),
                Collections.emptyList());
        PluginRiskDiff contextDiff = PluginRiskDiff.between(previous, contextChanged);
        require(contextDiff.packageSchemaChanged);
        require(contextDiff.analyzerVersionChanged);
        require(contextDiff.hostFingerprintChanged);
        require(PluginRiskPolicy.evaluateUpdate(previous, contextChanged).decision
                == PluginRiskPolicy.Decision.CONFIRM);

        PluginRiskReport otherPlugin = new PluginRiskReport(
                digest('d'),
                "other.plugin",
                "2.0.0",
                5,
                1,
                digest('0'),
                1234,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        expectInvalid(() -> PluginRiskDiff.between(previous, otherPlugin));
    }

    private static PluginRiskReport report(
            String archiveSha256,
            List<PluginRiskReport.ModuleDigest> modules,
            List<PluginRiskEvidence> evidence,
            List<String> limitations) {
        return new PluginRiskReport(
                archiveSha256,
                "example.plugin",
                "2.0.0",
                5,
                1,
                digest('0'),
                1234,
                modules,
                evidence,
                limitations);
    }

    private static PluginRiskEvidence evidence(
            String id,
            PluginRiskEvidence.Confidence confidence) {
        return new PluginRiskEvidence(
                id,
                null,
                PluginRiskEvidence.Source.DEX,
                confidence);
    }

    private static String digest(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static void expectInvalid(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError();
        } catch (IllegalArgumentException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void expectUnsupported(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError();
        } catch (UnsupportedOperationException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void expectIo(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError();
        } catch (IOException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
