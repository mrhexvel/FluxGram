package org.telegram.messenger.plugins.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PluginRuntimeJournalSnapshot {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_EVENTS = 512;
    public static final int MAX_EVENTS_PER_RUNTIME = 128;
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    private static final int MAGIC = 0x46474a52;
    private static final int CHECKSUM_BYTES = 32;

    public final long revision;
    public final long droppedEvents;
    public final long evictedEvents;
    public final List<PluginRuntimeJournalEvent> events;

    private PluginRuntimeJournalSnapshot(
            long revision,
            long droppedEvents,
            long evictedEvents,
            List<PluginRuntimeJournalEvent> events) {
        if (revision < 0
                || droppedEvents < 0
                || evictedEvents < 0
                || events == null
                || events.size() > MAX_EVENTS
                || revision != total(events.size(), droppedEvents, evictedEvents)) {
            throw new IllegalArgumentException("Invalid plugin runtime journal snapshot");
        }

        ArrayList<PluginRuntimeJournalEvent> copy = new ArrayList<>(events);
        validateEvents(copy);

        this.revision = revision;
        this.droppedEvents = droppedEvents;
        this.evictedEvents = evictedEvents;
        this.events = Collections.unmodifiableList(copy);
    }

    public static PluginRuntimeJournalSnapshot empty() {
        return new PluginRuntimeJournalSnapshot(0, 0, 0, Collections.emptyList());
    }

    public PluginRuntimeJournalSnapshot append(PluginRuntimeJournalEvent event) {
        if (event == null
                || !events.isEmpty()
                && event.sequence <= events.get(events.size() - 1).sequence) {
            throw new IllegalArgumentException("Plugin runtime journal sequence must increase");
        }

        ArrayList<PluginRuntimeJournalEvent> updated = new ArrayList<>(events);
        long evicted = evictedEvents;
        int sameRuntime = 0;

        for (PluginRuntimeJournalEvent existing : updated) {
            if (event.sameRuntime(existing)) {
                sameRuntime++;
            }
        }

        if (sameRuntime == MAX_EVENTS_PER_RUNTIME) {
            for (int i = 0; i < updated.size(); i++) {
                if (event.sameRuntime(updated.get(i))) {
                    updated.remove(i);
                    evicted = increment(evicted, 1);
                    break;
                }
            }
        }

        if (updated.size() == MAX_EVENTS) {
            updated.remove(0);
            evicted = increment(evicted, 1);
        }

        updated.add(event);
        return new PluginRuntimeJournalSnapshot(
                increment(revision, 1),
                droppedEvents,
                evicted,
                updated);
    }

    public PluginRuntimeJournalSnapshot recordDropped(long count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Invalid dropped plugin runtime journal count");
        }

        return new PluginRuntimeJournalSnapshot(
                increment(revision, count),
                increment(droppedEvents, count),
                evictedEvents,
                events);
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(revision);
            output.writeLong(droppedEvents);
            output.writeLong(evictedEvents);
            output.writeInt(events.size());

            for (PluginRuntimeJournalEvent event : events) {
                output.writeLong(event.sequence);
                output.writeLong(event.monotonicTimeMillis);
                output.writeLong(event.processInstanceId);
                output.writeLong(event.runtimeInstanceId);
                writeString(output, event.pluginId, 128);
                output.write(hex(event.archiveSha256));
                output.writeLong(event.generation);
                output.writeInt(event.kind.code);
                output.writeLong(event.subjectToken == null ? 0 : event.subjectToken.high());
                output.writeLong(event.subjectToken == null ? 0 : event.subjectToken.low());
                output.writeInt(event.value);
            }
        }

        byte[] payload = payloadBytes.toByteArray();
        ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream(payload.length + CHECKSUM_BYTES);
        encodedBytes.write(payload);
        encodedBytes.write(sha256(payload));
        byte[] encoded = encodedBytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Plugin runtime journal snapshot exceeds size limit");
        }

        return encoded;
    }

    public static PluginRuntimeJournalSnapshot decode(byte[] encoded) throws IOException {
        if (encoded == null
                || encoded.length <= CHECKSUM_BYTES
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin runtime journal snapshot size");
        }

        int payloadLength = encoded.length - CHECKSUM_BYTES;
        byte[] payload = new byte[payloadLength];
        byte[] expectedChecksum = new byte[CHECKSUM_BYTES];
        System.arraycopy(encoded, 0, payload, 0, payloadLength);
        System.arraycopy(encoded, payloadLength, expectedChecksum, 0, CHECKSUM_BYTES);

        if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            throw new IOException("Invalid plugin runtime journal snapshot checksum");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin runtime journal snapshot header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin runtime journal snapshot version");
            }

            long revision = input.readLong();
            long droppedEvents = input.readLong();
            long evictedEvents = input.readLong();
            int eventCount = input.readInt();

            if (eventCount < 0 || eventCount > MAX_EVENTS) {
                throw new IOException("Invalid plugin runtime journal event count");
            }

            ArrayList<PluginRuntimeJournalEvent> events = new ArrayList<>(eventCount);

            for (int i = 0; i < eventCount; i++) {
                long sequence = input.readLong();
                long monotonicTimeMillis = input.readLong();
                long processInstanceId = input.readLong();
                long runtimeInstanceId = input.readLong();
                String pluginId = readString(input, 128);
                byte[] archiveDigest = new byte[32];
                input.readFully(archiveDigest);
                long generation = input.readLong();
                PluginRuntimeJournalCatalog.Kind kind = PluginRuntimeJournalCatalog.kind(input.readInt());
                PluginRuntimeJournalEvent.SubjectToken subjectToken =
                        PluginRuntimeJournalEvent.SubjectToken.decode(
                                input.readLong(),
                                input.readLong());
                int value = input.readInt();

                events.add(new PluginRuntimeJournalEvent(
                        sequence,
                        monotonicTimeMillis,
                        processInstanceId,
                        runtimeInstanceId,
                        pluginId,
                        hex(archiveDigest),
                        generation,
                        kind,
                        subjectToken,
                        value));
            }

            if (input.read() != -1) {
                throw new IOException("Trailing plugin runtime journal snapshot data");
            }

            return new PluginRuntimeJournalSnapshot(
                    revision,
                    droppedEvents,
                    evictedEvents,
                    events);
        } catch (EOFException error) {
            throw new IOException("Truncated plugin runtime journal snapshot", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin runtime journal snapshot content", error);
        }
    }

    private static void validateEvents(List<PluginRuntimeJournalEvent> events) {
        long previousSequence = 0;
        Map<Long, Long> processTimes = new HashMap<>();
        Map<RuntimeIdentity, Integer> runtimeCounts = new HashMap<>();

        for (PluginRuntimeJournalEvent event : events) {

            if (event == null || event.sequence <= previousSequence) {
                throw new IllegalArgumentException("Invalid plugin runtime journal event order");
            }

            previousSequence = event.sequence;
            Long processTime = processTimes.get(event.processInstanceId);

            if (processTime != null && event.monotonicTimeMillis < processTime) {
                throw new IllegalArgumentException("Invalid plugin runtime journal time order");
            }

            processTimes.put(event.processInstanceId, event.monotonicTimeMillis);
            RuntimeIdentity runtimeIdentity = new RuntimeIdentity(event);
            int runtimeCount = runtimeCounts.containsKey(runtimeIdentity)
                    ? runtimeCounts.get(runtimeIdentity) + 1
                    : 1;

            if (runtimeCount > MAX_EVENTS_PER_RUNTIME) {
                throw new IllegalArgumentException("Plugin runtime journal runtime limit exceeded");
            }

            runtimeCounts.put(runtimeIdentity, runtimeCount);
        }
    }

    private static long increment(long value, long amount) {
        if (Long.MAX_VALUE - value < amount) {
            return Long.MAX_VALUE;
        }

        return value + amount;
    }

    private static long total(long first, long second, long third) {
        return increment(increment(first, second), third);
    }

    private static void writeString(DataOutputStream output, String value, int limit)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);

        if (encoded.length == 0 || encoded.length > limit) {
            throw new IOException("Invalid plugin runtime journal string");
        }

        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid plugin runtime journal string length");
        }

        byte[] encoded = new byte[length];
        input.readFully(encoded);

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid plugin runtime journal UTF-8", error);
        }
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            int offset = i * 2;
            result[i] = (byte) Integer.parseInt(value.substring(offset, offset + 2), 16);
        }

        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);

        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }

        return result.toString();
    }

    private static final class RuntimeIdentity {
        private final long processInstanceId;
        private final long runtimeInstanceId;
        private final String pluginId;
        private final String archiveSha256;
        private final long generation;

        private RuntimeIdentity(PluginRuntimeJournalEvent event) {
            processInstanceId = event.processInstanceId;
            runtimeInstanceId = event.runtimeInstanceId;
            pluginId = event.pluginId;
            archiveSha256 = event.archiveSha256;
            generation = event.generation;
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
                    && runtimeInstanceId == other.runtimeInstanceId
                    && generation == other.generation
                    && pluginId.equals(other.pluginId)
                    && archiveSha256.equals(other.archiveSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    processInstanceId,
                    runtimeInstanceId,
                    pluginId,
                    archiveSha256,
                    generation);
        }
    }
}
