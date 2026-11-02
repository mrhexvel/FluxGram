import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCatalog;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalEvent;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

public final class PluginRuntimeJournalCheck {
    private static final String ARCHIVE = repeat("ab", 32);
    private static final PluginRuntimeJournalEvent.SubjectToken SUBJECT =
            PluginRuntimeJournalEvent.SubjectToken.create();

    public static void main(String[] args) throws Exception {
        catalogIsStable();
        eventValidation();
        roundTripAndDeterminism();
        malformedSnapshots();
        retentionAndCounters();
        immutability();
    }

    private static void catalogIsStable() {
        boolean[] codes = new boolean[256];

        for (PluginRuntimeJournalCatalog.Kind kind : PluginRuntimeJournalCatalog.Kind.values()) {
            require(kind.code > 0 && kind.code < codes.length);
            require(!codes[kind.code]);
            require(PluginRuntimeJournalCatalog.kind(kind.code) == kind);
            codes[kind.code] = true;
        }

        require(PluginRuntimeJournalCatalog.kind(0) == null);
        require(PluginRuntimeJournalCatalog.kind(255) == null);
    }

    private static void eventValidation() {
        PluginRuntimeJournalEvent simple = event(
                1,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                0,
                0);
        require(simple.category() == PluginRuntimeJournalCatalog.Category.RUNTIME);
        require(simple.durationBucket() == null);

        PluginRuntimeJournalEvent duration = event(
                2,
                1,
                PluginRuntimeJournalCatalog.Kind.MANAGED_HOOK_BUDGET_OVERRUN,
                7,
                PluginRuntimeJournalEvent.DurationBucket.UNDER_SIXTEEN_MILLISECONDS.code);
        require(duration.durationBucket()
                == PluginRuntimeJournalEvent.DurationBucket.UNDER_SIXTEEN_MILLISECONDS);

        expectInvalid(() -> event(
                3,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                1,
                0));
        expectInvalid(() -> event(
                3,
                1,
                PluginRuntimeJournalCatalog.Kind.RESOURCE_REGISTERED,
                0,
                0));
        expectInvalid(() -> event(
                3,
                1,
                PluginRuntimeJournalCatalog.Kind.MANAGED_TASK_QUEUE_REJECTED,
                0,
                0));
        expectInvalid(() -> event(
                3,
                1,
                PluginRuntimeJournalCatalog.Kind.MANAGED_HOOK_BUDGET_OVERRUN,
                1,
                99));
        expectInvalid(() -> new PluginRuntimeJournalEvent(
                1,
                0,
                1,
                1,
                "bad",
                ARCHIVE,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0));
        expectInvalid(() -> new PluginRuntimeJournalEvent(
                1,
                0,
                1,
                1,
                "example.plugin",
                ARCHIVE.toUpperCase(),
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0));
    }

    private static void roundTripAndDeterminism() throws Exception {
        PluginRuntimeJournalSnapshot snapshot = PluginRuntimeJournalSnapshot.empty()
                .append(event(
                        1,
                        1,
                        PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_STARTED,
                        0,
                        0))
                .append(event(
                        2,
                        1,
                        PluginRuntimeJournalCatalog.Kind.EXTENSION_REGISTRATION_ACCEPTED,
                        10,
                        0))
                .append(event(
                        3,
                        1,
                        PluginRuntimeJournalCatalog.Kind.EXTENSION_BUDGET_OVERRUN,
                        10,
                        PluginRuntimeJournalEvent.DurationBucket.UNDER_FOUR_MILLISECONDS.code))
                .recordDropped(2);

        byte[] encoded = snapshot.encode();
        PluginRuntimeJournalSnapshot decoded = PluginRuntimeJournalSnapshot.decode(encoded);
        require(Arrays.equals(encoded, snapshot.encode()));
        require(Arrays.equals(encoded, decoded.encode()));
        require(decoded.revision == 5);
        require(decoded.droppedEvents == 2);
        require(decoded.evictedEvents == 0);
        require(decoded.events.equals(snapshot.events));
        require(encoded.length < PluginRuntimeJournalSnapshot.MAX_ENCODED_BYTES);

        PluginRuntimeJournalSnapshot golden = PluginRuntimeJournalSnapshot.empty()
                .append(event(
                        1,
                        1,
                        PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_STARTED,
                        0,
                        0))
                .append(event(
                        2,
                        1,
                        PluginRuntimeJournalCatalog.Kind.MANAGED_TASK_QUEUE_REJECTED,
                        0,
                        2))
                .append(event(
                        3,
                        1,
                        PluginRuntimeJournalCatalog.Kind.CLEANUP_REFERENCES_PENDING,
                        0,
                        1))
                .recordDropped(2);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(golden.encode()));
        require(digest.equals("a4330b39b7f8ef654ee579dbc1a39369ce9f84039ef4b92fdff4714dd6ed02ac"));
    }

    private static void malformedSnapshots() throws Exception {
        PluginRuntimeJournalSnapshot snapshot = PluginRuntimeJournalSnapshot.empty()
                .append(event(
                        1,
                        1,
                        PluginRuntimeJournalCatalog.Kind.RESOURCE_REGISTERED,
                        9,
                        0));
        byte[] encoded = snapshot.encode();

        byte[] corrupted = encoded.clone();
        corrupted[10] ^= 1;
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(corrupted));
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(
                Arrays.copyOf(encoded, encoded.length - 1)));
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(
                new byte[PluginRuntimeJournalSnapshot.MAX_ENCODED_BYTES + 1]));

        byte[] badMagic = encoded.clone();
        writeInt(badMagic, 0, 0);
        refreshChecksum(badMagic);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(badMagic));

        byte[] badVersion = encoded.clone();
        writeInt(badVersion, 4, 2);
        refreshChecksum(badVersion);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(badVersion));

        byte[] badCount = encoded.clone();
        writeInt(badCount, 32, PluginRuntimeJournalSnapshot.MAX_EVENTS + 1);
        refreshChecksum(badCount);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(badCount));

        byte[] inconsistentCounters = encoded.clone();
        writeLong(inconsistentCounters, 16, Long.MAX_VALUE);
        refreshChecksum(inconsistentCounters);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(inconsistentCounters));

        byte[] saturatedCounters = encoded.clone();
        writeLong(saturatedCounters, 8, Long.MAX_VALUE);
        writeLong(saturatedCounters, 16, Long.MAX_VALUE);
        writeLong(saturatedCounters, 24, 1);
        refreshChecksum(saturatedCounters);
        require(PluginRuntimeJournalSnapshot.decode(saturatedCounters).revision == Long.MAX_VALUE);

        int pluginOffset = 36 + 32 + 4;
        byte[] badUtf8 = encoded.clone();
        badUtf8[pluginOffset] = (byte) 0x80;
        refreshChecksum(badUtf8);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(badUtf8));

        int kindOffset = pluginOffset + "example.plugin".length() + 32 + 8;
        byte[] unknownKind = encoded.clone();
        writeInt(unknownKind, kindOffset, 999);
        refreshChecksum(unknownKind);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(unknownKind));

        byte[] invalidSubject = encoded.clone();
        writeLong(invalidSubject, kindOffset + 4, 0);
        writeLong(invalidSubject, kindOffset + 12, 0);
        refreshChecksum(invalidSubject);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(invalidSubject));

        byte[] trailing = insertBeforeChecksum(encoded, (byte) 1);
        refreshChecksum(trailing);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(trailing));

        PluginRuntimeJournalSnapshot pair = snapshot.append(event(
                2,
                1,
                PluginRuntimeJournalCatalog.Kind.RESOURCE_CLOSED,
                9,
                0));
        byte[] unordered = pair.encode();
        int secondEventOffset = eventEndOffset(unordered, 36);
        writeLong(unordered, secondEventOffset, 1);
        refreshChecksum(unordered);
        expectIo(() -> PluginRuntimeJournalSnapshot.decode(unordered));

        PluginRuntimeJournalEvent earlierTime = new PluginRuntimeJournalEvent(
                2,
                5,
                11,
                1,
                "example.plugin",
                ARCHIVE,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0);
        expectInvalid(() -> PluginRuntimeJournalSnapshot.empty()
                .append(event(
                        1,
                        1,
                        PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                        0,
                        0))
                .append(earlierTime));

        PluginRuntimeJournalEvent nextProcess = new PluginRuntimeJournalEvent(
                2,
                1,
                12,
                1,
                "example.plugin",
                ARCHIVE,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                null,
                0);
        require(PluginRuntimeJournalSnapshot.empty()
                .append(event(
                        1,
                        1,
                        PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                        0,
                        0))
                .append(nextProcess)
                .events.size() == 2);
    }

    private static void retentionAndCounters() {
        PluginRuntimeJournalSnapshot perRuntime = PluginRuntimeJournalSnapshot.empty();

        for (int i = 1; i <= PluginRuntimeJournalSnapshot.MAX_EVENTS_PER_RUNTIME + 1; i++) {
            perRuntime = perRuntime.append(event(
                    i,
                    1,
                    PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                    0,
                    0));
        }

        require(perRuntime.events.size() == PluginRuntimeJournalSnapshot.MAX_EVENTS_PER_RUNTIME);
        require(perRuntime.events.get(0).sequence == 2);
        require(perRuntime.evictedEvents == 1);

        PluginRuntimeJournalSnapshot global = PluginRuntimeJournalSnapshot.empty();
        long sequence = 1;

        for (int runtime = 1; runtime <= 4; runtime++) {
            for (int i = 0; i < PluginRuntimeJournalSnapshot.MAX_EVENTS_PER_RUNTIME; i++) {
                global = global.append(event(
                        sequence++,
                        runtime,
                        PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                        0,
                        0));
            }
        }

        require(global.events.size() == PluginRuntimeJournalSnapshot.MAX_EVENTS);
        global = global.append(event(
                sequence,
                5,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                0,
                0));
        require(global.events.size() == PluginRuntimeJournalSnapshot.MAX_EVENTS);
        require(global.events.get(0).sequence == 2);
        require(global.evictedEvents == 1);

        PluginRuntimeJournalSnapshot saturated = PluginRuntimeJournalSnapshot.empty()
                .recordDropped(Long.MAX_VALUE)
                .recordDropped(1);
        require(saturated.revision == Long.MAX_VALUE);
        require(saturated.droppedEvents == Long.MAX_VALUE);
        expectInvalid(() -> PluginRuntimeJournalSnapshot.empty().recordDropped(0));
        expectInvalid(() -> PluginRuntimeJournalSnapshot.empty().append(event(
                2,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                0,
                0)).append(event(
                1,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                0,
                0)));
    }

    private static void immutability() {
        PluginRuntimeJournalSnapshot before = PluginRuntimeJournalSnapshot.empty();
        PluginRuntimeJournalSnapshot after = before.append(event(
                1,
                1,
                PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING,
                0,
                0));
        require(before.events.isEmpty());
        require(after.events.size() == 1);

        try {
            after.events.clear();
            throw new AssertionError("Expected immutable event list");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static PluginRuntimeJournalEvent event(
            long sequence,
            long runtime,
            PluginRuntimeJournalCatalog.Kind kind,
            long subject,
            int value) {
        return new PluginRuntimeJournalEvent(
                sequence,
                sequence * 10,
                11,
                runtime,
                "example.plugin",
                ARCHIVE,
                1,
                kind,
                subject == 0 ? null : SUBJECT,
                value);
    }

    private static int eventEndOffset(byte[] encoded, int offset) {
        int pluginLengthOffset = offset + 32;
        int pluginLength = readInt(encoded, pluginLengthOffset);
        return pluginLengthOffset + 4 + pluginLength + 32 + 8 + 4 + 16 + 4;
    }

    private static byte[] insertBeforeChecksum(byte[] encoded, byte value) {
        int payloadLength = encoded.length - 32;
        ByteArrayOutputStream result = new ByteArrayOutputStream(encoded.length + 1);
        result.write(encoded, 0, payloadLength);
        result.write(value);
        result.write(encoded, payloadLength, 32);
        return result.toByteArray();
    }

    private static void refreshChecksum(byte[] encoded) throws Exception {
        int payloadLength = encoded.length - 32;
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(
                Arrays.copyOf(encoded, payloadLength));
        System.arraycopy(checksum, 0, encoded, payloadLength, checksum.length);
    }

    private static int readInt(byte[] value, int offset) {
        return (value[offset] & 0xff) << 24
                | (value[offset + 1] & 0xff) << 16
                | (value[offset + 2] & 0xff) << 8
                | value[offset + 3] & 0xff;
    }

    private static void writeInt(byte[] value, int offset, int item) {
        value[offset] = (byte) (item >>> 24);
        value[offset + 1] = (byte) (item >>> 16);
        value[offset + 2] = (byte) (item >>> 8);
        value[offset + 3] = (byte) item;
    }

    private static void writeLong(byte[] value, int offset, long item) {
        for (int i = 7; i >= 0; i--) {
            value[offset + i] = (byte) item;
            item >>>= 8;
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);

        for (int i = 0; i < count; i++) {
            result.append(value);
        }

        return result.toString();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);

        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }

        return result.toString();
    }

    private static void expectInvalid(CheckedRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void expectIo(CheckedRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException");
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

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
