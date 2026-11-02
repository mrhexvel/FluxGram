package org.telegram.messenger.plugins.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public final class PluginRuntimeJournalStore implements AutoCloseable {
    public enum Name {
        STATE,
        STATE_TEMPORARY,
        INTENT,
        INTENT_TEMPORARY
    }

    public interface FileOperations extends AutoCloseable {
        default void acquire() throws IOException {
        }

        byte[] read(Name name, int maximumBytes) throws IOException;

        void writeSynced(Name name, byte[] value) throws IOException;

        void atomicReplace(Name source, Name target) throws IOException;

        void delete(Name name) throws IOException;

        void syncDirectory() throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    public enum Recovery {
        CLEAN,
        ROLLED_FORWARD,
        RESET_CORRUPT_STATE,
        IGNORED_CORRUPT_INTENT,
        IGNORED_STALE_INTENT
    }

    public enum CommitStatus {
        COMMITTED,
        ALREADY_COMMITTED
    }

    public static final class LoadResult {
        public final PluginRuntimeJournalSnapshot snapshot;
        public final Recovery recovery;
        public final long nextSequence;
        public final boolean sequenceExhausted;
        public final long nextBatchId;
        public final boolean batchExhausted;
        public final long nextMonotonicTimeMillis;

        private LoadResult(State state, Recovery recovery, long currentProcessInstanceId) {
            snapshot = state.snapshot;
            this.recovery = recovery;

            if (snapshot.events.isEmpty()) {
                nextSequence = 1;
                sequenceExhausted = false;
            } else {
                long lastSequence = snapshot.events.get(snapshot.events.size() - 1).sequence;
                nextSequence = lastSequence == Long.MAX_VALUE ? Long.MAX_VALUE : lastSequence + 1;
                sequenceExhausted = lastSequence == Long.MAX_VALUE;
            }

            if (state.lastProcessInstanceId == 0
                    || state.lastProcessInstanceId != currentProcessInstanceId) {
                nextBatchId = 1;
                batchExhausted = false;
            } else {
                nextBatchId = state.lastBatchId == Long.MAX_VALUE
                        ? Long.MAX_VALUE
                        : state.lastBatchId + 1;
                batchExhausted = state.lastBatchId == Long.MAX_VALUE;
            }

            long processTime = 0;

            for (PluginRuntimeJournalEvent event : snapshot.events) {
                if (event.processInstanceId == currentProcessInstanceId) {
                    processTime = event.monotonicTimeMillis;
                }
            }

            nextMonotonicTimeMillis = processTime;
        }
    }

    public static final class CommitResult {
        public final CommitStatus status;
        public final PluginRuntimeJournalSnapshot snapshot;

        private CommitResult(CommitStatus status, PluginRuntimeJournalSnapshot snapshot) {
            this.status = status;
            this.snapshot = snapshot;
        }
    }

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_STATE_BYTES = PluginRuntimeJournalSnapshot.MAX_ENCODED_BYTES + 128;
    public static final int MAX_INTENT_BYTES = MAX_STATE_BYTES + 128;

    private static final int STATE_MAGIC = 0x46474a53;
    private static final int INTENT_MAGIC = 0x46474a49;
    private static final int CHECKSUM_BYTES = 32;
    private static final byte[] ABSENT_DIGEST = new byte[CHECKSUM_BYTES];

    private final FileOperations files;
    private final long processInstanceId;
    private final Thread ownerThread;

    private State state;
    private byte[] stateBytes;
    private boolean loaded;
    private boolean closing;
    private boolean closed;

    public PluginRuntimeJournalStore(FileOperations files, long processInstanceId) throws IOException {
        if (files == null || processInstanceId <= 0) {
            throw new IllegalArgumentException("Invalid plugin runtime journal store");
        }

        this.files = files;
        this.processInstanceId = processInstanceId;
        ownerThread = Thread.currentThread();
        files.acquire();
    }

    public LoadResult load() throws IOException {
        checkOpen();
        loaded = false;
        state = null;
        stateBytes = null;

        try {
            LoadResult result = loadInternal();
            loaded = true;
            return result;
        } catch (IOException error) {
            state = null;
            stateBytes = null;
            throw error;
        }
    }

    public CommitResult commit(PluginRuntimeJournalCollector.Batch batch) throws IOException {
        checkOpen();

        if (batch == null
                || batch.isEmpty()
                || batch.batchId <= 0
                || batch.processInstanceId != processInstanceId) {
            throw new IllegalArgumentException("Invalid plugin runtime journal batch");
        }

        if (!loaded) {
            loadInternal();
            loaded = true;
        }

        byte[] batchDigest = batchDigest(batch);

        if (state.lastProcessInstanceId == processInstanceId
                && state.lastBatchId == batch.batchId) {
            if (!MessageDigest.isEqual(state.lastBatchDigest, batchDigest)) {
                throw new IOException("Conflicting plugin runtime journal batch replay");
            }

            return new CommitResult(CommitStatus.ALREADY_COMMITTED, state.snapshot);
        }

        if (state.lastProcessInstanceId == processInstanceId
                && state.lastBatchId == Long.MAX_VALUE) {
            throw new IOException("Plugin runtime journal batch sequence exhausted");
        }
        if (state.storeGeneration == Long.MAX_VALUE) {
            throw new IOException("Plugin runtime journal store generation exhausted");
        }

        long expectedBatch = state.lastProcessInstanceId == processInstanceId
                ? state.lastBatchId + 1
                : 1;

        if (batch.batchId != expectedBatch) {
            throw new IOException("Out-of-order plugin runtime journal batch");
        }

        PluginRuntimeJournalSnapshot next = state.snapshot;

        try {
            for (PluginRuntimeJournalEvent event : batch.events) {
                if (event.processInstanceId != processInstanceId) {
                    throw new IOException("Plugin runtime journal event process mismatch");
                }

                next = next.append(event);
            }

            if (batch.droppedEvents > 0) {
                next = next.recordDropped(batch.droppedEvents);
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin runtime journal batch content", error);
        }

        State after = new State(
                state.storeGeneration + 1,
                processInstanceId,
                batch.batchId,
                batchDigest,
                next);
        byte[] afterBytes = after.encode();
        Intent intent = new Intent(
                stateBytes == null ? ABSENT_DIGEST : sha256(stateBytes),
                afterBytes);

        try {
            writeAtomic(Name.INTENT_TEMPORARY, Name.INTENT, intent.encode());
            recoverIntent(intent);
            return new CommitResult(CommitStatus.COMMITTED, state.snapshot);
        } catch (IOException error) {
            loaded = false;
            throw error;
        }
    }

    public PluginRuntimeJournalSnapshot snapshot() {
        checkOpen();

        if (!loaded) {
            throw new IllegalStateException("Plugin runtime journal store is not loaded");
        }

        return state.snapshot;
    }

    @Override
    public void close() throws IOException {
        checkOwner();

        if (closed) {
            return;
        }

        closing = true;
        files.close();
        closed = true;
        loaded = false;
        state = null;
        stateBytes = null;
    }

    private LoadResult loadInternal() throws IOException {
        byte[] encodedIntent = files.read(Name.INTENT, MAX_INTENT_BYTES);
        Recovery recovery = Recovery.CLEAN;

        if (encodedIntent != null) {
            try {
                Intent intent = Intent.decode(encodedIntent);
                recovery = recoverIntent(intent);
            } catch (MalformedDataException error) {
                files.delete(Name.INTENT);
                files.syncDirectory();
                recovery = Recovery.IGNORED_CORRUPT_INTENT;
            }
        }

        if (recovery != Recovery.ROLLED_FORWARD
                && recovery != Recovery.IGNORED_STALE_INTENT) {
            byte[] encodedState = files.read(Name.STATE, MAX_STATE_BYTES);

            if (encodedState == null) {
                state = State.empty();
                stateBytes = null;
            } else {
                try {
                    state = State.decode(encodedState);
                    stateBytes = encodedState.clone();
                } catch (MalformedDataException error) {
                    state = State.empty();
                    stateBytes = state.encode();
                    writeAtomic(Name.STATE_TEMPORARY, Name.STATE, stateBytes);
                    recovery = Recovery.RESET_CORRUPT_STATE;
                }
            }
        }

        return new LoadResult(state, recovery, processInstanceId);
    }

    private Recovery recoverIntent(Intent intent) throws IOException {
        State after;

        try {
            after = State.decode(intent.afterState);
        } catch (MalformedDataException error) {
            throw new MalformedDataException("Invalid plugin runtime journal intent state", error);
        }

        byte[] current = files.read(Name.STATE, MAX_STATE_BYTES);

        if (current != null && !MessageDigest.isEqual(current, intent.afterState)) {
            try {
                State currentState = State.decode(current);

                if (currentState.storeGeneration >= after.storeGeneration) {
                    files.delete(Name.INTENT);
                    files.syncDirectory();
                    state = currentState;
                    stateBytes = current.clone();
                    return Recovery.IGNORED_STALE_INTENT;
                }
            } catch (MalformedDataException ignored) {
            }
        }

        if (!MessageDigest.isEqual(current == null ? new byte[0] : current, intent.afterState)) {
            writeAtomic(Name.STATE_TEMPORARY, Name.STATE, intent.afterState);
        }

        files.delete(Name.INTENT);
        files.syncDirectory();
        state = after;
        stateBytes = intent.afterState.clone();
        return Recovery.ROLLED_FORWARD;
    }

    private void writeAtomic(Name temporary, Name target, byte[] value) throws IOException {
        files.writeSynced(temporary, value);
        files.atomicReplace(temporary, target);
        files.syncDirectory();
    }

    private void checkOpen() {
        checkOwner();

        if (closing || closed) {
            throw new IllegalStateException("Plugin runtime journal store is closed");
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Plugin runtime journal store owner thread changed");
        }
    }

    private static byte[] appendChecksum(byte[] payload, int maximumBytes) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + CHECKSUM_BYTES);
        encoded.write(payload);
        encoded.write(sha256(payload));
        byte[] result = encoded.toByteArray();

        if (result.length > maximumBytes) {
            throw new IOException("Plugin runtime journal persisted value exceeds size limit");
        }

        return result;
    }

    private static byte[] verifiedPayload(byte[] encoded, int maximumBytes) throws MalformedDataException {
        if (encoded == null
                || encoded.length <= CHECKSUM_BYTES
                || encoded.length > maximumBytes) {
            throw new MalformedDataException("Invalid plugin runtime journal persisted size");
        }

        int payloadLength = encoded.length - CHECKSUM_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] checksum = Arrays.copyOfRange(encoded, payloadLength, encoded.length);

        try {
            if (!MessageDigest.isEqual(checksum, sha256(payload))) {
                throw new MalformedDataException("Invalid plugin runtime journal persisted checksum");
            }
        } catch (IOException error) {
            throw new MalformedDataException("Plugin runtime journal checksum is unavailable", error);
        }

        return payload;
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] batchDigest(PluginRuntimeJournalCollector.Batch batch) throws IOException {
        PluginRuntimeJournalSnapshot batchSnapshot = PluginRuntimeJournalSnapshot.empty();

        for (PluginRuntimeJournalEvent event : batch.events) {
            batchSnapshot = batchSnapshot.append(event);
        }

        if (batch.droppedEvents > 0) {
            batchSnapshot = batchSnapshot.recordDropped(batch.droppedEvents);
        }

        byte[] snapshotBytes = batchSnapshot.encode();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeLong(batch.processInstanceId);
            output.writeLong(batch.batchId);
            output.writeInt(snapshotBytes.length);
            output.write(snapshotBytes);
        }

        return sha256(bytes.toByteArray());
    }

    private static final class State {
        private final long storeGeneration;
        private final long lastProcessInstanceId;
        private final long lastBatchId;
        private final byte[] lastBatchDigest;
        private final PluginRuntimeJournalSnapshot snapshot;

        private State(
                long storeGeneration,
                long lastProcessInstanceId,
                long lastBatchId,
                byte[] lastBatchDigest,
                PluginRuntimeJournalSnapshot snapshot) {
            if (snapshot == null
                    || lastBatchDigest == null
                    || lastBatchDigest.length != CHECKSUM_BYTES
                    || lastProcessInstanceId < 0
                    || lastBatchId < 0
                    || storeGeneration < 0
                    || (lastProcessInstanceId == 0) != (lastBatchId == 0)
                    || (storeGeneration == 0) != (lastProcessInstanceId == 0)
                    || (storeGeneration == 0) != (snapshot.revision == 0)
                    || (lastProcessInstanceId == 0)
                            != MessageDigest.isEqual(lastBatchDigest, ABSENT_DIGEST)) {
                throw new IllegalArgumentException("Invalid plugin runtime journal persisted state");
            }

            this.storeGeneration = storeGeneration;
            this.lastProcessInstanceId = lastProcessInstanceId;
            this.lastBatchId = lastBatchId;
            this.lastBatchDigest = lastBatchDigest.clone();
            this.snapshot = snapshot;
        }

        private static State empty() {
            return new State(
                    0,
                    0,
                    0,
                    ABSENT_DIGEST,
                    PluginRuntimeJournalSnapshot.empty());
        }

        private byte[] encode() throws IOException {
            byte[] snapshotBytes = snapshot.encode();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(STATE_MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(storeGeneration);
                output.writeLong(lastProcessInstanceId);
                output.writeLong(lastBatchId);
                output.write(lastBatchDigest);
                output.writeInt(snapshotBytes.length);
                output.write(snapshotBytes);
            }

            return appendChecksum(bytes.toByteArray(), MAX_STATE_BYTES);
        }

        private static State decode(byte[] encoded) throws MalformedDataException {
            byte[] payload = verifiedPayload(encoded, MAX_STATE_BYTES);

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != STATE_MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw new MalformedDataException("Unsupported plugin runtime journal state");
                }

                long storeGeneration = input.readLong();
                long processInstanceId = input.readLong();
                long batchId = input.readLong();
                byte[] batchDigest = new byte[CHECKSUM_BYTES];
                input.readFully(batchDigest);
                int snapshotLength = input.readInt();

                if (snapshotLength <= 0
                        || snapshotLength > PluginRuntimeJournalSnapshot.MAX_ENCODED_BYTES) {
                    throw new MalformedDataException("Invalid plugin runtime journal snapshot length");
                }

                byte[] snapshotBytes = new byte[snapshotLength];
                input.readFully(snapshotBytes);

                if (input.read() != -1) {
                    throw new MalformedDataException("Trailing plugin runtime journal state data");
                }

                return new State(
                        storeGeneration,
                        processInstanceId,
                        batchId,
                        batchDigest,
                        PluginRuntimeJournalSnapshot.decode(snapshotBytes));
            } catch (EOFException error) {
                throw new MalformedDataException("Truncated plugin runtime journal state", error);
            } catch (IOException | IllegalArgumentException error) {
                if (error instanceof MalformedDataException) {
                    throw (MalformedDataException) error;
                }

                throw new MalformedDataException("Invalid plugin runtime journal state", error);
            }
        }
    }

    private static final class Intent {
        private final byte[] beforeStateDigest;
        private final byte[] afterState;

        private Intent(byte[] beforeStateDigest, byte[] afterState) {
            if (beforeStateDigest == null
                    || beforeStateDigest.length != CHECKSUM_BYTES
                    || afterState == null
                    || afterState.length == 0
                    || afterState.length > MAX_STATE_BYTES) {
                throw new IllegalArgumentException("Invalid plugin runtime journal intent");
            }

            this.beforeStateDigest = beforeStateDigest.clone();
            this.afterState = afterState.clone();
        }

        private byte[] encode() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(INTENT_MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.write(beforeStateDigest);
                output.writeInt(afterState.length);
                output.write(afterState);
            }

            return appendChecksum(bytes.toByteArray(), MAX_INTENT_BYTES);
        }

        private static Intent decode(byte[] encoded) throws MalformedDataException {
            byte[] payload = verifiedPayload(encoded, MAX_INTENT_BYTES);

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != INTENT_MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw new MalformedDataException("Unsupported plugin runtime journal intent");
                }

                byte[] beforeDigest = new byte[CHECKSUM_BYTES];
                input.readFully(beforeDigest);
                int afterLength = input.readInt();

                if (afterLength <= 0 || afterLength > MAX_STATE_BYTES) {
                    throw new MalformedDataException("Invalid plugin runtime journal intent length");
                }

                byte[] after = new byte[afterLength];
                input.readFully(after);

                if (input.read() != -1) {
                    throw new MalformedDataException("Trailing plugin runtime journal intent data");
                }

                State.decode(after);
                return new Intent(beforeDigest, after);
            } catch (EOFException error) {
                throw new MalformedDataException("Truncated plugin runtime journal intent", error);
            } catch (IOException | IllegalArgumentException error) {
                if (error instanceof MalformedDataException) {
                    throw (MalformedDataException) error;
                }

                throw new MalformedDataException("Invalid plugin runtime journal intent", error);
            }
        }
    }

    private static final class MalformedDataException extends IOException {
        private MalformedDataException(String message) {
            super(message);
        }

        private MalformedDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
