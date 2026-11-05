package org.telegram.messenger.plugins.journal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class PluginRuntimeJournalExport {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENCODED_BYTES = 512 * 1024;

    private final byte[] encoded;

    private PluginRuntimeJournalExport(byte[] encoded) {
        this.encoded = encoded;
    }

    public static PluginRuntimeJournalExport create(
            PluginRuntimeJournalSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            throw new IllegalArgumentException("Plugin runtime journal snapshot is required");
        }

        StringBuilder output = new StringBuilder(4096);
        Map<Long, Integer> processes = new LinkedHashMap<>();
        Map<Long, Long> processStartTimes = new HashMap<>();
        Map<RuntimeIdentity, Integer> runtimes = new LinkedHashMap<>();
        Map<PluginRuntimeJournalEvent.SubjectToken, Integer> subjects = new LinkedHashMap<>();

        output.append('{');
        field(output, "format", "fluxgram-plugin-runtime-journal");
        output.append(',');
        field(output, "version", FORMAT_VERSION);
        output.append(',');
        field(output, "coverage", "host_managed_fluxgram_api_only");
        output.append(',');
        output.append("\"not_observed\":[");
        string(output, "direct_xposed_or_aliuhook");
        output.append(',');
        string(output, "reflection");
        output.append(',');
        string(output, "raw_network_files_or_database");
        output.append(',');
        string(output, "plugin_owned_threads");
        output.append(',');
        string(output, "jni_or_native_code");
        output.append(']');
        output.append(',');
        field(output, "revision", snapshot.revision);
        output.append(',');
        field(output, "dropped_events", snapshot.droppedEvents);
        output.append(',');
        field(output, "evicted_events", snapshot.evictedEvents);
        output.append(',');
        field(output, "event_count", snapshot.events.size());
        output.append(',');
        output.append("\"events\":[");

        for (int index = 0; index < snapshot.events.size(); index++) {
            PluginRuntimeJournalEvent event = snapshot.events.get(index);

            if (index > 0) {
                output.append(',');
            }

            int process = alias(processes, event.processInstanceId);
            RuntimeIdentity runtimeIdentity = new RuntimeIdentity(event);
            int runtime = alias(runtimes, runtimeIdentity);
            long processStart = processStartTimes.containsKey(event.processInstanceId)
                    ? processStartTimes.get(event.processInstanceId)
                    : event.monotonicTimeMillis;
            processStartTimes.put(event.processInstanceId, processStart);

            output.append('{');
            field(output, "sequence", event.sequence);
            output.append(',');
            field(output, "time_offset_ms", event.monotonicTimeMillis - processStart);
            output.append(',');
            field(output, "process", "process-" + process);
            output.append(',');
            field(output, "runtime", "runtime-" + runtime);
            output.append(',');
            field(output, "plugin_id", event.pluginId);
            output.append(',');
            field(output, "archive_sha256", event.archiveSha256);
            output.append(',');
            field(output, "generation", event.generation);
            output.append(',');
            field(output, "category_code", event.category().code);
            output.append(',');
            field(output, "category", name(event.category().name()));
            output.append(',');
            field(output, "kind_code", event.kind.code);
            output.append(',');
            field(output, "kind", name(event.kind.name()));
            output.append(',');
            field(output, "value_type", name(event.kind.valueType.name()));
            output.append(',');
            output.append("\"subject\":");

            if (event.subjectToken == null) {
                output.append("null");
            } else {
                string(output, "subject-" + alias(subjects, event.subjectToken));
            }

            output.append(',');
            output.append("\"value\":");

            if (event.kind.valueType == PluginRuntimeJournalCatalog.ValueType.NONE) {
                output.append("null");
            } else {
                output.append(event.value);
            }

            output.append('}');
        }

        output.append(']');
        output.append('}');

        byte[] encoded = output.toString().getBytes(StandardCharsets.UTF_8);

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Plugin runtime journal export exceeds size limit");
        }

        return new PluginRuntimeJournalExport(encoded);
    }

    public byte[] bytes() {
        return encoded.clone();
    }

    private static <T> int alias(Map<T, Integer> aliases, T identity) {
        Integer existing = aliases.get(identity);

        if (existing != null) {
            return existing;
        }

        int created = aliases.size() + 1;
        aliases.put(identity, created);
        return created;
    }

    private static void field(StringBuilder output, String name, String value) {
        string(output, name);
        output.append(':');
        string(output, value);
    }

    private static void field(StringBuilder output, String name, long value) {
        string(output, name);
        output.append(':');
        output.append(value);
    }

    private static void string(StringBuilder output, String value) {
        output.append('"');

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                    break;
            }
        }

        output.append('"');
    }

    private static String name(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static final class RuntimeIdentity {
        private final long processInstanceId;
        private final long runtimeInstanceId;

        private RuntimeIdentity(PluginRuntimeJournalEvent event) {
            processInstanceId = event.processInstanceId;
            runtimeInstanceId = event.runtimeInstanceId;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof RuntimeIdentity)) {
                return false;
            }

            RuntimeIdentity other = (RuntimeIdentity) object;
            return processInstanceId == other.processInstanceId
                    && runtimeInstanceId == other.runtimeInstanceId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(processInstanceId, runtimeInstanceId);
        }
    }
}
