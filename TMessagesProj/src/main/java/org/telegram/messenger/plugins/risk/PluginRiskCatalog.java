package org.telegram.messenger.plugins.risk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PluginRiskCatalog {
    public enum Disposition {
        OBSERVE,
        CONFIRM,
        BLOCK
    }

    public enum Severity {
        INFORMATIONAL,
        ELEVATED,
        HIGH,
        CRITICAL
    }

    public static final class Descriptor {
        public final String id;
        public final Severity severity;
        public final Disposition disposition;
        public final boolean known;

        private Descriptor(
                String id,
                Severity severity,
                Disposition disposition,
                boolean known) {
            this.id = id;
            this.severity = severity;
            this.disposition = disposition;
            this.known = known;
        }
    }

    private static final Map<String, Descriptor> DESCRIPTORS = createDescriptors();

    private PluginRiskCatalog() {
    }

    public static Descriptor resolve(String id) {
        if (!PluginRiskEvidence.isValidId(id)) {
            throw new IllegalArgumentException("Invalid risk evidence ID");
        }

        Descriptor descriptor = DESCRIPTORS.get(id);

        if (descriptor != null) {
            return descriptor;
        }

        if (id.startsWith("block.")) {
            return new Descriptor(id, Severity.CRITICAL, Disposition.BLOCK, false);
        }

        return new Descriptor(id, Severity.HIGH, Disposition.CONFIRM, false);
    }

    public static Map<String, Descriptor> descriptors() {
        return DESCRIPTORS;
    }

    private static Map<String, Descriptor> createDescriptors() {
        LinkedHashMap<String, Descriptor> descriptors = new LinkedHashMap<>();

        add(descriptors, "access.full_process.python", Severity.INFORMATIONAL, Disposition.OBSERVE);
        add(descriptors, "access.full_process.dex", Severity.INFORMATIONAL, Disposition.OBSERVE);
        add(descriptors, "python.top_level.preflight", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "python.top_level_side_effect", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "python.shared_interpreter", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "python.internal_runtime_import", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "bridge.java_direct", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "host.telegram_internal_direct", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "host.plugin_internal_direct", Severity.HIGH, Disposition.CONFIRM);

        add(descriptors, "hook.managed_method", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "hook.direct_xposed", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "hook.direct_aliuhook", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "reflection.general", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "reflection.access_override", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "code.dynamic_class_loading", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "code.native_loading", Severity.CRITICAL, Disposition.CONFIRM);
        add(descriptors, "process.shell_execution", Severity.CRITICAL, Disposition.CONFIRM);
        add(descriptors, "thread.unmanaged", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "python.dynamic_execution", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "python.global_runtime_mutation", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "lifecycle.private_override", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "lifecycle.unmanaged_resource", Severity.ELEVATED, Disposition.OBSERVE);

        add(descriptors, "host.account_or_session_access", Severity.CRITICAL, Disposition.CONFIRM);
        add(descriptors, "host.message_data_access", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "host.message_mutation", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "network.raw", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "network.endpoint_literal", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "filesystem.unmanaged", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "storage.managed", Severity.INFORMATIONAL, Disposition.OBSERVE);
        add(descriptors, "storage.cross_plugin_attempt", Severity.CRITICAL, Disposition.CONFIRM);
        add(descriptors, "ui.managed", Severity.INFORMATIONAL, Disposition.OBSERVE);
        add(descriptors, "ui.direct_android", Severity.ELEVATED, Disposition.OBSERVE);
        add(descriptors, "ui.low_level_draw_replacement", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "android.sensitive_api", Severity.HIGH, Disposition.CONFIRM);

        add(descriptors, "analysis.dynamic_target", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "analysis.obfuscated_or_dynamic", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "analysis.unsupported_python", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "analysis.limit_exceeded", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "analysis.incomplete", Severity.CRITICAL, Disposition.BLOCK);

        for (String category : PluginPythonRiskScanner.CATEGORIES) {
            add(descriptors, "declaration.missing." + category, Severity.HIGH, Disposition.CONFIRM);
            add(descriptors, "declaration.unobserved." + category, Severity.ELEVATED, Disposition.OBSERVE);
        }

        add(descriptors, "update.module_composition_changed", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "update.package_schema_changed", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "update.analyzer_version_changed", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "update.host_fingerprint_changed", Severity.HIGH, Disposition.CONFIRM);
        add(descriptors, "update.previous_report_unavailable", Severity.HIGH, Disposition.CONFIRM);

        add(descriptors, "block.archive.native_payload_forbidden", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "block.archive.undeclared_executable", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "block.dex.host_class_bundled", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "block.module.digest_mismatch", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "block.module.incompatible", Severity.CRITICAL, Disposition.BLOCK);
        add(descriptors, "block.host_fingerprint_mismatch", Severity.CRITICAL, Disposition.BLOCK);

        return Collections.unmodifiableMap(descriptors);
    }

    private static void add(
            Map<String, Descriptor> descriptors,
            String id,
            Severity severity,
            Disposition disposition) {
        Descriptor previous = descriptors.put(
                id,
                new Descriptor(id, severity, disposition, true));

        if (previous != null) {
            throw new IllegalStateException("Duplicate risk evidence ID");
        }
    }
}
