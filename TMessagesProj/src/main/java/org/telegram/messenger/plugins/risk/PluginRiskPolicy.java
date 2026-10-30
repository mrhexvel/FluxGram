package org.telegram.messenger.plugins.risk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class PluginRiskPolicy {
    public enum Decision {
        ALLOW,
        CONFIRM,
        BLOCK
    }

    public static final class Result {
        public final Decision decision;
        public final PluginRiskCatalog.Severity maximumSeverity;
        public final List<String> reasons;

        private Result(
                Decision decision,
                PluginRiskCatalog.Severity maximumSeverity,
                List<String> reasons) {
            this.decision = decision;
            this.maximumSeverity = maximumSeverity;
            this.reasons = Collections.unmodifiableList(reasons);
        }
    }

    private PluginRiskPolicy() {
    }

    public static Result evaluateInstall(PluginRiskReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Risk report is required");
        }

        return evaluate(
                report,
                report.evidence,
                report.limitations,
                Collections.emptyList());
    }

    public static Result evaluateUpdate(
            PluginRiskReport previous,
            PluginRiskReport current) {
        PluginRiskDiff diff = PluginRiskDiff.between(previous, current);
        ArrayList<PluginRiskEvidence> relevant = new ArrayList<>();

        for (PluginRiskEvidence item : current.evidence) {
            if (PluginRiskCatalog.resolve(item.id).disposition
                    == PluginRiskCatalog.Disposition.BLOCK) {
                relevant.add(item);
            }
        }

        relevant.addAll(diff.addedEvidence);

        ArrayList<String> contextReasons = new ArrayList<>();

        if (!diff.addedModules.isEmpty() || !diff.removedModules.isEmpty()) {
            contextReasons.add("update.module_composition_changed");
        }
        if (diff.packageSchemaChanged) {
            contextReasons.add("update.package_schema_changed");
        }
        if (diff.analyzerVersionChanged) {
            contextReasons.add("update.analyzer_version_changed");
        }
        if (diff.hostFingerprintChanged) {
            contextReasons.add("update.host_fingerprint_changed");
        }

        return evaluate(current, relevant, current.limitations, contextReasons);
    }

    private static Result evaluate(
            PluginRiskReport report,
            List<PluginRiskEvidence> relevant,
            List<String> limitations,
            List<String> contextReasons) {
        Decision decision = contextReasons.isEmpty() ? Decision.ALLOW : Decision.CONFIRM;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        reasons.addAll(contextReasons);

        for (String limitation : limitations) {
            PluginRiskCatalog.Descriptor descriptor = PluginRiskCatalog.resolve(limitation);
            reasons.add(limitation);

            if (descriptor.disposition == PluginRiskCatalog.Disposition.BLOCK) {
                decision = Decision.BLOCK;
            } else if (decision != Decision.BLOCK) {
                decision = Decision.CONFIRM;
            }
        }

        for (PluginRiskEvidence item : relevant) {
            PluginRiskCatalog.Descriptor descriptor = PluginRiskCatalog.resolve(item.id);

            if (descriptor.disposition == PluginRiskCatalog.Disposition.BLOCK) {
                decision = Decision.BLOCK;
                reasons.add(item.id);
                continue;
            }

            boolean requiresConfirmation = !descriptor.known
                    || item.confidence == PluginRiskEvidence.Confidence.UNKNOWN
                    || descriptor.disposition == PluginRiskCatalog.Disposition.CONFIRM;

            if (requiresConfirmation && decision != Decision.BLOCK) {
                decision = Decision.CONFIRM;
                reasons.add(item.id);
            }
        }

        PluginRiskCatalog.Severity maximumSeverity = report.maximumSeverity();

        for (String reason : contextReasons) {
            PluginRiskCatalog.Severity severity = PluginRiskCatalog.resolve(reason).severity;

            if (severity.compareTo(maximumSeverity) > 0) {
                maximumSeverity = severity;
            }
        }

        return new Result(
                decision,
                maximumSeverity,
                new ArrayList<>(reasons));
    }
}
