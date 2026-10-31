package org.telegram.messenger.plugins.reputation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class PluginReputationCache {
    public enum UpdateStatus {
        ACCEPTED,
        UNCHANGED,
        REJECTED_INVALID,
        REJECTED_AUTHORITY,
        REJECTED_FUTURE,
        REJECTED_OLD,
        REJECTED_CONFLICT,
        REJECTED_CAPACITY
    }

    public static final class UpdateResult {
        public final UpdateStatus status;
        public final PluginReputationCache cache;

        private UpdateResult(UpdateStatus status, PluginReputationCache cache) {
            this.status = status;
            this.cache = cache;
        }
    }

    public static final class LookupResult {
        public final PluginReputationPolicy.Decision decision;
        public final boolean authenticated;
        public final boolean fresh;
        public final boolean stale;
        public final boolean usedBlockingAnchor;
        public final List<String> reasons;

        private LookupResult(
                PluginReputationPolicy.Decision decision,
                boolean authenticated,
                boolean fresh,
                boolean stale,
                boolean usedBlockingAnchor,
                List<String> reasons) {
            this.decision = decision;
            this.authenticated = authenticated;
            this.fresh = fresh;
            this.stale = stale;
            this.usedBlockingAnchor = usedBlockingAnchor;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 128;
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    private static final int MAGIC = 0x46475243;
    private static final int CHECKSUM_BYTES = 32;
    private static final int MAX_RESPONSE_BYTES = 128;
    private static final int MAX_ENVELOPE_BYTES = 512;

    private static final Comparator<Entry> EVICTION_ORDER = (first, second) -> {
        int compared = Long.compare(
                first.current.response.expiresAtMillis,
                second.current.response.expiresAtMillis);
        if (compared != 0) {
            return compared;
        }

        compared = Long.compare(
                first.current.response.issuedAtMillis,
                second.current.response.issuedAtMillis);
        if (compared != 0) {
            return compared;
        }

        compared = Long.compare(
                first.current.response.revision,
                second.current.response.revision);
        if (compared != 0) {
            return compared;
        }

        return first.identity.compareTo(second.identity);
    };

    private final long generation;
    private final String authorityKeyId;
    private final TreeMap<Identity, Entry> entries;

    private PluginReputationCache(
            long generation,
            String authorityKeyId,
            Map<Identity, Entry> entries) {
        this.generation = generation;
        this.authorityKeyId = authorityKeyId;
        this.entries = new TreeMap<>(entries);
    }

    public static PluginReputationCache empty() {
        return new PluginReputationCache(0, null, Collections.emptyMap());
    }

    public long generation() {
        return generation;
    }

    public int size() {
        return entries.size();
    }

    public UpdateResult update(
            byte[] encodedResponse,
            byte[] encodedEnvelope,
            String authorityKeyId,
            long nowMillis) {
        validateLookupInput(authorityKeyId, nowMillis);

        if (this.authorityKeyId != null && !this.authorityKeyId.equals(authorityKeyId)) {
            return updateResult(UpdateStatus.REJECTED_AUTHORITY, this);
        }

        SignedRecord candidate;

        try {
            candidate = authenticateRecord(
                    encodedResponse,
                    encodedEnvelope,
                    authorityKeyId,
                    nowMillis);
        } catch (IOException error) {
            return updateResult(UpdateStatus.REJECTED_INVALID, this);
        }

        if (candidate.verification.status == PluginReputationVerification.Status.NOT_YET_VALID) {
            return updateResult(UpdateStatus.REJECTED_FUTURE, this);
        }
        if (candidate.verification.status != PluginReputationVerification.Status.VERIFIED
                && candidate.verification.status != PluginReputationVerification.Status.EXPIRED) {
            return updateResult(UpdateStatus.REJECTED_INVALID, this);
        }

        Identity identity = Identity.of(candidate.response);
        Entry current = entries.get(identity);

        if (current != null) {
            if (candidate.response.revision < current.current.response.revision) {
                return updateResult(UpdateStatus.REJECTED_OLD, this);
            }
            if (candidate.response.revision == current.current.response.revision) {
                if (candidate.exactlyEquals(current.current)) {
                    return updateResult(UpdateStatus.UNCHANGED, this);
                }

                return updateResult(UpdateStatus.REJECTED_CONFLICT, this);
            }
        }
        if (generation == Long.MAX_VALUE) {
            return updateResult(UpdateStatus.REJECTED_CAPACITY, this);
        }

        TreeMap<Identity, Entry> updated = new TreeMap<>(entries);

        if (current == null && updated.size() == MAX_ENTRIES) {
            Entry eviction = null;

            for (Entry entry : updated.values()) {
                if (!entry.isProtected()
                        && (eviction == null || EVICTION_ORDER.compare(entry, eviction) < 0)) {
                    eviction = entry;
                }
            }

            if (eviction == null) {
                return updateResult(UpdateStatus.REJECTED_CAPACITY, this);
            }

            updated.remove(eviction.identity);
        }

        SignedRecord anchor = null;

        if (!isBlocking(candidate.response) && current != null) {
            anchor = current.isProtected() ? current.blockingRecord() : null;
        }

        updated.put(identity, new Entry(identity, candidate, anchor));
        PluginReputationCache result = new PluginReputationCache(
                generation + 1,
                authorityKeyId,
                updated);

        try {
            result.encode();
        } catch (IOException error) {
            return updateResult(UpdateStatus.REJECTED_CAPACITY, this);
        }

        return updateResult(UpdateStatus.ACCEPTED, result);
    }

    public LookupResult lookup(
            String archiveSha256,
            String developerKeyId,
            String authorityKeyId,
            long nowMillis) {
        validateIdentity(archiveSha256, developerKeyId);
        validateLookupInput(authorityKeyId, nowMillis);

        if (this.authorityKeyId != null && !this.authorityKeyId.equals(authorityKeyId)) {
            throw new IllegalArgumentException("Plugin reputation cache authority mismatch");
        }

        Entry entry = entries.get(new Identity(archiveSha256, developerKeyId));

        if (entry == null) {
            return unavailable();
        }

        SignedRecord selected = entry.current;
        boolean usedAnchor = false;

        if (!isBlocking(selected.response)
                && nowMillis < selected.response.issuedAtMillis
                && entry.anchor != null) {
            selected = entry.anchor;
            usedAnchor = true;
        }

        PluginReputationVerification verification = PluginReputationVerifier.verifyEncoded(
                selected.responseBytes,
                selected.envelopeBytes,
                authorityKeyId,
                archiveSha256,
                developerKeyId,
                nowMillis);

        if (!verification.isAuthenticated()) {
            return unavailable();
        }

        boolean fresh = verification.status == PluginReputationVerification.Status.VERIFIED;
        boolean blocking = isBlocking(selected.response);
        ArrayList<String> reasons = new ArrayList<>();

        if (selected.response.archiveStatus
                == PluginReputationResponse.ArchiveStatus.MALICIOUS) {
            reasons.add(PluginReputationPolicy.BLOCK_ARCHIVE);
        }
        if (selected.response.keyStatus == PluginReputationResponse.KeyStatus.REVOKED) {
            reasons.add(PluginReputationPolicy.BLOCK_KEY);
        }
        if (!blocking && !fresh) {
            reasons.add(PluginReputationPolicy.RECORD_STALE);
        }

        return new LookupResult(
                blocking
                        ? PluginReputationPolicy.Decision.BLOCK
                        : PluginReputationPolicy.Decision.NO_ASSERTION,
                true,
                fresh,
                !fresh,
                usedAnchor,
                reasons);
    }

    public byte[] currentResponseBytes(String archiveSha256, String developerKeyId) {
        Entry entry = getEntry(archiveSha256, developerKeyId);
        return entry == null ? null : entry.current.responseBytes.clone();
    }

    public byte[] currentEnvelopeBytes(String archiveSha256, String developerKeyId) {
        Entry entry = getEntry(archiveSha256, developerKeyId);
        return entry == null ? null : entry.current.envelopeBytes.clone();
    }

    public byte[] blockingAnchorResponseBytes(String archiveSha256, String developerKeyId) {
        Entry entry = getEntry(archiveSha256, developerKeyId);
        return entry == null || entry.anchor == null ? null : entry.anchor.responseBytes.clone();
    }

    public byte[] blockingAnchorEnvelopeBytes(String archiveSha256, String developerKeyId) {
        Entry entry = getEntry(archiveSha256, developerKeyId);
        return entry == null || entry.anchor == null ? null : entry.anchor.envelopeBytes.clone();
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(generation);
            output.writeInt(entries.size());

            for (Entry entry : entries.values()) {
                writeRecord(output, entry.current);
                output.writeInt(entry.anchor == null ? 0 : 1);

                if (entry.anchor != null) {
                    writeRecord(output, entry.anchor);
                }
            }
        }

        byte[] payload = payloadBytes.toByteArray();
        byte[] checksum = sha256(payload);

        if (payload.length > MAX_ENCODED_BYTES - CHECKSUM_BYTES) {
            throw new IOException("Plugin reputation cache exceeds size limit");
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + CHECKSUM_BYTES);
        encoded.write(payload);
        encoded.write(checksum);
        return encoded.toByteArray();
    }

    public static PluginReputationCache decode(
            byte[] encoded,
            String authorityKeyId,
            long nowMillis) throws IOException {
        return decodeInternal(encoded, authorityKeyId, nowMillis);
    }

    public static PluginReputationCache restore(
            byte[] encoded,
            String authorityKeyId,
            long nowMillis,
            PluginReputationCache baseline) throws IOException {
        validateLookupInput(authorityKeyId, nowMillis);

        if (baseline == null) {
            throw new IllegalArgumentException("Plugin reputation cache baseline is required");
        }
        if (baseline.authorityKeyId != null
                && !baseline.authorityKeyId.equals(authorityKeyId)) {
            throw new IllegalArgumentException("Plugin reputation cache authority mismatch");
        }

        if (encoded == null) {
            throw new IOException("Invalid plugin reputation cache size");
        }

        byte[] snapshot = encoded.clone();
        PluginReputationCache restored = decodeInternal(snapshot, authorityKeyId, nowMillis);
        validateBaseline(restored, baseline, snapshot);
        return restored;
    }

    private static PluginReputationCache decodeInternal(
            byte[] encoded,
            String authorityKeyId,
            long nowMillis) throws IOException {
        validateLookupInput(authorityKeyId, nowMillis);

        if (encoded == null
                || encoded.length < 20 + CHECKSUM_BYTES
                || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin reputation cache size");
        }

        int payloadLength = encoded.length - CHECKSUM_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] expectedChecksum = Arrays.copyOfRange(encoded, payloadLength, encoded.length);

        if (!MessageDigest.isEqual(expectedChecksum, sha256(payload))) {
            throw new IOException("Invalid plugin reputation cache checksum");
        }

        TreeMap<Identity, Entry> entries = new TreeMap<>();
        long generation;

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin reputation cache header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin reputation cache version");
            }

            generation = input.readLong();
            int count = input.readInt();

            if (generation < 0 || count < 0 || count > MAX_ENTRIES) {
                throw new IOException("Invalid plugin reputation cache bounds");
            }

            Identity previous = null;

            for (int i = 0; i < count; i++) {
                SignedRecord current = readRecord(input, authorityKeyId, nowMillis);
                Identity identity = Identity.of(current.response);

                if (previous != null && previous.compareTo(identity) >= 0) {
                    throw new IOException("Unsorted or duplicate plugin reputation identity");
                }

                int hasAnchor = input.readInt();
                SignedRecord anchor;

                if (hasAnchor == 0) {
                    anchor = null;
                } else if (hasAnchor == 1) {
                    anchor = readRecord(input, authorityKeyId, nowMillis);
                } else {
                    throw new IOException("Invalid plugin reputation anchor flag");
                }

                Entry entry = new Entry(identity, current, anchor);
                validateAnchor(entry);
                entries.put(identity, entry);
                previous = identity;
            }

            if (input.read() != -1) {
                throw new IOException("Trailing plugin reputation cache data");
            }
        } catch (EOFException error) {
            throw new IOException("Truncated plugin reputation cache", error);
        }

        return new PluginReputationCache(generation, authorityKeyId, entries);
    }

    private static void validateBaseline(
            PluginReputationCache restored,
            PluginReputationCache baseline,
            byte[] encoded) throws IOException {
        if (restored.generation < baseline.generation) {
            throw new IOException("Plugin reputation cache generation rollback");
        }
        if (restored.generation == baseline.generation) {
            if (!MessageDigest.isEqual(baseline.encode(), encoded)) {
                throw new IOException("Conflicting plugin reputation cache generation");
            }

            return;
        }

        validateRestoredFutureRecords(restored, baseline);

        for (Entry previous : baseline.entries.values()) {
            Entry current = restored.entries.get(previous.identity);

            if (current == null) {
                if (previous.isProtected()) {
                    throw new IOException("Protected plugin reputation entry was lost");
                }

                continue;
            }

            long previousRevision = previous.current.response.revision;
            long currentRevision = current.current.response.revision;

            if (currentRevision < previousRevision) {
                throw new IOException("Plugin reputation revision rollback");
            }
            if (currentRevision == previousRevision
                    && !current.current.exactlyEquals(previous.current)) {
                throw new IOException("Conflicting plugin reputation revision");
            }
            if (current.anchor != null && !validRestoredAnchor(current, previous)) {
                throw new IOException("Plugin reputation anchor rollback");
            }
            if (previous.isProtected() && !preservesProtection(current, previous)) {
                throw new IOException("Plugin reputation protection was lost");
            }
        }
    }

    private static void validateRestoredFutureRecords(
            PluginReputationCache restored,
            PluginReputationCache baseline) throws IOException {
        for (Entry current : restored.entries.values()) {
            Entry previous = baseline.entries.get(current.identity);

            if (current.current.verification.status
                    == PluginReputationVerification.Status.NOT_YET_VALID
                    && (previous == null
                            || !current.current.exactlyEquals(previous.current))) {
                throw new IOException("New future plugin reputation response");
            }
            if (current.anchor != null
                    && current.anchor.verification.status
                            == PluginReputationVerification.Status.NOT_YET_VALID
                    && (previous == null
                            || previous.blockingRecord() == null
                            || !current.anchor.exactlyEquals(previous.blockingRecord()))) {
                throw new IOException("New future plugin reputation anchor");
            }
        }
    }

    private static boolean preservesProtection(Entry current, Entry previous) {
        if (isBlocking(current.current.response)) {
            return true;
        }
        if (current.anchor == null) {
            return false;
        }

        SignedRecord previousBlock = previous.blockingRecord();

        return current.anchor.exactlyEquals(previousBlock)
                || current.anchor.response.revision > previous.current.response.revision;
    }

    private static boolean validRestoredAnchor(Entry current, Entry previous) {
        SignedRecord previousBlock = previous.blockingRecord();

        if (previousBlock != null && current.anchor.exactlyEquals(previousBlock)) {
            return true;
        }

        return current.anchor.response.revision > previous.current.response.revision
                && current.anchor.response.revision < current.current.response.revision;
    }

    private Entry getEntry(String archiveSha256, String developerKeyId) {
        validateIdentity(archiveSha256, developerKeyId);
        return entries.get(new Identity(archiveSha256, developerKeyId));
    }

    private static SignedRecord readRecord(
            DataInputStream input,
            String authorityKeyId,
            long nowMillis) throws IOException {
        byte[] response = readBytes(input, MAX_RESPONSE_BYTES);
        byte[] envelope = readBytes(input, MAX_ENVELOPE_BYTES);
        return authenticateRecord(response, envelope, authorityKeyId, nowMillis);
    }

    private static SignedRecord authenticateRecord(
            byte[] responseBytes,
            byte[] envelopeBytes,
            String authorityKeyId,
            long nowMillis) throws IOException {
        if (responseBytes == null
                || responseBytes.length == 0
                || responseBytes.length > MAX_RESPONSE_BYTES
                || envelopeBytes == null
                || envelopeBytes.length == 0
                || envelopeBytes.length > MAX_ENVELOPE_BYTES) {
            throw new IOException("Invalid plugin reputation record size");
        }

        byte[] responseCopy = responseBytes.clone();
        byte[] envelopeCopy = envelopeBytes.clone();
        PluginReputationResponse response = PluginReputationResponse.decode(responseCopy);
        PluginReputationVerification verification = PluginReputationVerifier.verifyEncoded(
                responseCopy,
                envelopeCopy,
                authorityKeyId,
                response.archiveSha256,
                response.developerKeyId,
                nowMillis);

        if (!verification.isAuthenticated()) {
            throw new IOException("Unauthenticated plugin reputation record");
        }

        return new SignedRecord(response, responseCopy, envelopeCopy, verification);
    }

    private static void validateAnchor(Entry entry) throws IOException {
        if (entry.anchor == null) {
            return;
        }
        if (isBlocking(entry.current.response)
                || !isBlocking(entry.anchor.response)
                || !entry.identity.equals(Identity.of(entry.anchor.response))
                || entry.anchor.response.revision >= entry.current.response.revision) {
            throw new IOException("Invalid plugin reputation blocking anchor");
        }
    }

    private static void writeRecord(DataOutputStream output, SignedRecord record) throws IOException {
        writeBytes(output, record.responseBytes, MAX_RESPONSE_BYTES);
        writeBytes(output, record.envelopeBytes, MAX_ENVELOPE_BYTES);
    }

    private static void writeBytes(DataOutputStream output, byte[] value, int limit)
            throws IOException {
        if (value.length == 0 || value.length > limit) {
            throw new IOException("Invalid plugin reputation cache field size");
        }

        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid plugin reputation cache field length");
        }

        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static boolean isBlocking(PluginReputationResponse response) {
        return response.archiveStatus == PluginReputationResponse.ArchiveStatus.MALICIOUS
                || response.keyStatus == PluginReputationResponse.KeyStatus.REVOKED;
    }

    private static LookupResult unavailable() {
        return new LookupResult(
                PluginReputationPolicy.Decision.NO_ASSERTION,
                false,
                false,
                false,
                false,
                Collections.singletonList(PluginReputationPolicy.RECORD_UNAVAILABLE));
    }

    private static UpdateResult updateResult(
            UpdateStatus status,
            PluginReputationCache cache) {
        return new UpdateResult(status, cache);
    }

    private static void validateLookupInput(String authorityKeyId, long nowMillis) {
        if (!PluginReputationResponse.isKeyId(authorityKeyId) || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin reputation cache input");
        }
    }

    private static void validateIdentity(String archiveSha256, String developerKeyId) {
        if (!PluginReputationResponse.isSha256(archiveSha256)
                || developerKeyId != null
                        && !PluginReputationResponse.isKeyId(developerKeyId)) {
            throw new IllegalArgumentException("Invalid plugin reputation query identity");
        }
    }

    private static final class Identity implements Comparable<Identity> {
        private final String archiveSha256;
        private final String developerKeyId;

        private Identity(String archiveSha256, String developerKeyId) {
            this.archiveSha256 = archiveSha256;
            this.developerKeyId = developerKeyId;
        }

        private static Identity of(PluginReputationResponse response) {
            return new Identity(response.archiveSha256, response.developerKeyId);
        }

        @Override
        public int compareTo(Identity other) {
            int compared = archiveSha256.compareTo(other.archiveSha256);

            if (compared != 0) {
                return compared;
            }
            if (developerKeyId == null) {
                return other.developerKeyId == null ? 0 : -1;
            }
            if (other.developerKeyId == null) {
                return 1;
            }

            return developerKeyId.compareTo(other.developerKeyId);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Identity)) {
                return false;
            }

            Identity other = (Identity) object;
            return archiveSha256.equals(other.archiveSha256)
                    && Objects.equals(developerKeyId, other.developerKeyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(archiveSha256, developerKeyId);
        }
    }

    private static final class SignedRecord {
        private final PluginReputationResponse response;
        private final byte[] responseBytes;
        private final byte[] envelopeBytes;
        private final PluginReputationVerification verification;

        private SignedRecord(
                PluginReputationResponse response,
                byte[] responseBytes,
                byte[] envelopeBytes,
                PluginReputationVerification verification) {
            this.response = response;
            this.responseBytes = responseBytes.clone();
            this.envelopeBytes = envelopeBytes.clone();
            this.verification = verification;
        }

        private boolean exactlyEquals(SignedRecord other) {
            return Arrays.equals(responseBytes, other.responseBytes)
                    && Arrays.equals(envelopeBytes, other.envelopeBytes);
        }
    }

    private static final class Entry {
        private final Identity identity;
        private final SignedRecord current;
        private final SignedRecord anchor;

        private Entry(Identity identity, SignedRecord current, SignedRecord anchor) {
            this.identity = identity;
            this.current = current;
            this.anchor = anchor;
        }

        private boolean isProtected() {
            return isBlocking(current.response) || anchor != null;
        }

        private SignedRecord blockingRecord() {
            return isBlocking(current.response) ? current : anchor;
        }
    }
}
