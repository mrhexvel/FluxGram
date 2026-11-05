import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCatalog;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalEvent;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalExport;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PluginRuntimeJournalExportCheck {
    private static final String ARCHIVE = repeat("ef", 32);

    public static void main(String[] args) throws Exception {
        deterministicRedactedExport();
        emptyExport();
    }

    private static void deterministicRedactedExport() throws Exception {
        PluginRuntimeJournalEvent.SubjectToken subject =
                PluginRuntimeJournalEvent.SubjectToken.create();
        PluginRuntimeJournalSnapshot snapshot = PluginRuntimeJournalSnapshot.empty()
                .append(new PluginRuntimeJournalEvent(
                        1,
                        900000001,
                        700000001,
                        800000001,
                        "example.plugin",
                        ARCHIVE,
                        1,
                        PluginRuntimeJournalCatalog.Kind.EXTENSION_REGISTRATION_ACCEPTED,
                        subject,
                        0))
                .append(new PluginRuntimeJournalEvent(
                        2,
                        900000009,
                        700000001,
                        800000001,
                        "example.plugin",
                        ARCHIVE,
                        1,
                        PluginRuntimeJournalCatalog.Kind.EXTENSION_CALLBACK_FAILED,
                        subject,
                        0))
                .recordDropped(3);
        byte[] before = snapshot.encode();
        PluginRuntimeJournalExport first = PluginRuntimeJournalExport.create(snapshot);
        PluginRuntimeJournalExport second = PluginRuntimeJournalExport.create(snapshot);
        byte[] encoded = first.bytes();
        String json = new String(encoded, StandardCharsets.UTF_8);

        require(Arrays.equals(encoded, second.bytes()));
        require(Arrays.equals(before, snapshot.encode()));
        require(encoded.length < PluginRuntimeJournalExport.MAX_ENCODED_BYTES);
        require(json.startsWith("{\"format\":\"fluxgram-plugin-runtime-journal\""));
        require(json.contains("\"coverage\":\"host_managed_fluxgram_api_only\""));
        require(json.contains("\"direct_xposed_or_aliuhook\""));
        require(json.contains("\"jni_or_native_code\""));
        require(json.contains("\"revision\":5"));
        require(json.contains("\"dropped_events\":3"));
        require(json.contains("\"event_count\":2"));
        require(json.contains("\"process\":\"process-1\""));
        require(json.contains("\"runtime\":\"runtime-1\""));
        require(json.contains("\"time_offset_ms\":0"));
        require(json.contains("\"time_offset_ms\":8"));
        require(json.contains("\"subject\":\"subject-1\""));
        require(json.contains("\"kind\":\"extension_callback_failed\""));
        require(json.contains("\"archive_sha256\":\"" + ARCHIVE + "\""));
        require(!json.contains("700000001"));
        require(!json.contains("800000001"));
        require(!json.contains("900000001"));

        byte[] returned = first.bytes();
        returned[0] = 0;
        require(first.bytes()[0] == '{');
    }

    private static void emptyExport() throws Exception {
        String json = new String(
                PluginRuntimeJournalExport.create(PluginRuntimeJournalSnapshot.empty()).bytes(),
                StandardCharsets.UTF_8);
        require(json.contains("\"revision\":0"));
        require(json.contains("\"events\":[]"));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);

        for (int i = 0; i < count; i++) {
            result.append(value);
        }

        return result.toString();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Plugin runtime journal export check failed");
        }
    }
}
