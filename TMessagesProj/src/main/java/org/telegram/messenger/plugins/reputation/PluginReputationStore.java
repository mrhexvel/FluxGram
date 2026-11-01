package org.telegram.messenger.plugins.reputation;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class PluginReputationStore {
    public static final class Snapshot {
        public final PluginReputationCache cache;

        private final Token token;

        private Snapshot(PluginReputationCache cache, Token token) {
            this.cache = cache;
            this.token = token;
        }

        public long generation() {
            return token.generation;
        }
    }

    public static final class UpdateResult {
        public final PluginReputationCache.UpdateStatus status;
        public final Snapshot snapshot;
        public final boolean persisted;

        private UpdateResult(
                PluginReputationCache.UpdateStatus status,
                Snapshot snapshot,
                boolean persisted) {
            this.status = status;
            this.snapshot = snapshot;
            this.persisted = persisted;
        }
    }

    public static final int FORMAT_VERSION = 1;
    public static final int TRANSACTION_FORMAT_VERSION = 1;
    public static final int MAX_SNAPSHOT_BYTES = PluginReputationCache.MAX_ENCODED_BYTES + 256;
    public static final int MAX_TRANSACTION_BYTES = MAX_SNAPSHOT_BYTES + 256;

    static final String BASELINE_FILE = "reputation.baseline";
    static final String CURRENT_FILE = "reputation.current";
    static final String TRANSACTION_FILE = "reputation.transaction";
    static final String LOCK_FILE = "reputation.lock";
    static final String BASELINE_TEMP_FILE = "reputation.baseline.tmp";
    static final String CURRENT_TEMP_FILE = "reputation.current.tmp";
    static final String TRANSACTION_TEMP_FILE = "reputation.transaction.tmp";
    static final String BASELINE_BACKUP_FILE = "reputation.baseline.backup";
    static final String CURRENT_BACKUP_FILE = "reputation.current.backup";

    private static final int SNAPSHOT_MAGIC = 0x46475253;
    private static final int TRANSACTION_MAGIC = 0x46475254;
    private static final int STORE_ID_BYTES = 32;
    private static final int CHECKSUM_BYTES = 32;
    private static final int AUTHORITY_BYTES = 69;
    private static final byte[] ABSENT_DIGEST = new byte[CHECKSUM_BYTES];
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private final String authorityKeyId;
    private final FileOperations files;
    private final Object lock;

    public PluginReputationStore(File root, String authorityKeyId) throws IOException {
        this(root, authorityKeyId, new AndroidFileOperations(root));
    }

    PluginReputationStore(File root, String authorityKeyId, FileOperations files) throws IOException {
        if (root == null || files == null || !PluginReputationResponse.isKeyId(authorityKeyId)) {
            throw new IllegalArgumentException("Invalid plugin reputation store input");
        }

        this.authorityKeyId = authorityKeyId;
        this.files = files;
        files.validateRoot();

        String canonicalRoot = root.getCanonicalPath();

        synchronized (LOCKS) {
            Object existing = LOCKS.get(canonicalRoot);

            if (existing == null) {
                existing = new Object();
                LOCKS.put(canonicalRoot, existing);
            }

            lock = existing;
        }
    }

    public Snapshot load(long nowMillis) throws IOException {
        validateNow(nowMillis);

        synchronized (lock) {
            try (LockHandle ignored = files.lock()) {
                return loadLocked(nowMillis);
            }
        }
    }

    public UpdateResult update(
            Snapshot expected,
            byte[] encodedResponse,
            byte[] encodedEnvelope,
            long nowMillis) throws IOException {
        validateNow(nowMillis);

        if (expected == null || expected.token == null) {
            throw new IllegalArgumentException("Plugin reputation store snapshot is required");
        }

        synchronized (lock) {
            try (LockHandle ignored = files.lock()) {
                Snapshot current = loadLocked(nowMillis);

                if (!current.token.exactlyEquals(expected.token)) {
                    throw new IOException("Stale plugin reputation store snapshot");
                }

                PluginReputationCache.UpdateResult result = current.cache.update(
                        encodedResponse,
                        encodedEnvelope,
                        authorityKeyId,
                        nowMillis);

                if (result.status != PluginReputationCache.UpdateStatus.ACCEPTED) {
                    return new UpdateResult(result.status, current, false);
                }
                if (result.cache.generation() != current.cache.generation() + 1) {
                    throw new IOException("Invalid plugin reputation cache generation transition");
                }

                byte[] storeId = current.token.storeId;
                byte[] after = encodeSnapshot(storeId, result.cache);
                Transaction transaction = new Transaction(
                        storeId,
                        authorityKeyId,
                        current.token.generation,
                        current.token.snapshotDigest,
                        nowMillis,
                        after);
                Snapshot persisted = commitTransaction(transaction, nowMillis);
                return new UpdateResult(result.status, persisted, true);
            }
        }
    }

    private Snapshot loadLocked(long nowMillis) throws IOException {
        files.validateRoot();

        if (files.exists(TRANSACTION_FILE)) {
            recoverTransaction(nowMillis);
        }

        if (!files.exists(BASELINE_FILE)) {
            initialize(nowMillis);
        }

        byte[] baselineBytes = files.read(BASELINE_FILE, MAX_SNAPSHOT_BYTES);
        Snapshot baseline = decodeSnapshot(baselineBytes, nowMillis);
        stabilizeCurrent(baselineBytes, baseline, nowMillis);
        cleanupStableArtifacts();
        return baseline;
    }

    private void initialize(long nowMillis) throws IOException {
        String[] names = files.list();

        for (String name : names) {
            if (!LOCK_FILE.equals(name) && !isTemporary(name)) {
                throw new IOException("Plugin reputation store baseline is missing");
            }
        }

        cleanupTemporaryArtifacts();

        byte[] storeId = files.randomBytes(STORE_ID_BYTES);

        if (storeId == null || storeId.length != STORE_ID_BYTES) {
            throw new IOException("Invalid plugin reputation store identity");
        }

        byte[] after = encodeSnapshot(storeId, PluginReputationCache.empty());
        Transaction transaction = new Transaction(
                storeId,
                authorityKeyId,
                -1,
                ABSENT_DIGEST,
                nowMillis,
                after);
        commitTransaction(transaction, nowMillis);
    }

    private Snapshot commitTransaction(Transaction transaction, long nowMillis) throws IOException {
        byte[] encoded = transaction.encode();
        writeAtomic(TRANSACTION_TEMP_FILE, TRANSACTION_FILE, encoded);
        recoverTransaction(nowMillis);

        byte[] persisted = files.read(BASELINE_FILE, MAX_SNAPSHOT_BYTES);

        if (!MessageDigest.isEqual(persisted, transaction.afterSnapshot)) {
            throw new IOException("Plugin reputation persisted snapshot mismatch");
        }

        return decodeSnapshot(persisted, Math.max(nowMillis, transaction.validatedAtMillis));
    }

    private void recoverTransaction(long nowMillis) throws IOException {
        byte[] encoded = files.read(TRANSACTION_FILE, MAX_TRANSACTION_BYTES);
        Transaction transaction = Transaction.decode(encoded, authorityKeyId);
        long validationNow = Math.max(nowMillis, transaction.validatedAtMillis);
        Snapshot after = decodeSnapshot(transaction.afterSnapshot, validationNow);

        if (!MessageDigest.isEqual(after.token.storeId, transaction.storeId)
                || after.token.generation != transaction.afterGeneration) {
            throw new IOException("Plugin reputation transaction identity mismatch");
        }

        boolean baselineExists = files.exists(BASELINE_FILE);
        byte[] baselineBytes = baselineExists
                ? files.read(BASELINE_FILE, MAX_SNAPSHOT_BYTES)
                : null;
        Snapshot baseline = baselineExists ? decodeSnapshot(baselineBytes, nowMillis) : null;
        boolean baselineIsBefore = baseline != null
                && transaction.matchesBefore(baseline.token);
        boolean baselineIsAfter = baselineBytes != null
                && MessageDigest.isEqual(baselineBytes, transaction.afterSnapshot);

        if (transaction.beforeGeneration == -1) {
            if (baselineExists && !baselineIsAfter) {
                throw new IOException("Invalid plugin reputation initialization transaction");
            }
            if (!baselineExists) {
                validateInitializationArtifacts(transaction.afterSnapshot);
            }
        } else {
            if (!baselineIsBefore && !baselineIsAfter) {
                throw new IOException("Plugin reputation transaction baseline mismatch");
            }
            if (baselineIsBefore) {
                PluginReputationCache.restore(
                        after.token.cacheBytes,
                        authorityKeyId,
                        validationNow,
                        baseline.cache);
            }
        }

        if (!matchesFile(CURRENT_FILE, transaction.afterSnapshot)) {
            writeAtomic(CURRENT_TEMP_FILE, CURRENT_FILE, transaction.afterSnapshot);
        }
        if (!baselineIsAfter) {
            writeAtomic(BASELINE_TEMP_FILE, BASELINE_FILE, transaction.afterSnapshot);
        }

        byte[] verifiedBaseline = files.read(BASELINE_FILE, MAX_SNAPSHOT_BYTES);
        byte[] verifiedCurrent = files.read(CURRENT_FILE, MAX_SNAPSHOT_BYTES);

        if (!MessageDigest.isEqual(verifiedBaseline, transaction.afterSnapshot)
                || !MessageDigest.isEqual(verifiedCurrent, transaction.afterSnapshot)) {
            throw new IOException("Plugin reputation transaction verification failed");
        }

        decodeSnapshot(verifiedBaseline, validationNow);
        decodeSnapshot(verifiedCurrent, validationNow);
        files.delete(TRANSACTION_FILE);
        files.syncDirectory();
    }

    private void stabilizeCurrent(
            byte[] baselineBytes,
            Snapshot baseline,
            long nowMillis) throws IOException {
        boolean rewrite = true;

        if (files.exists(CURRENT_FILE)) {
            try {
                byte[] currentBytes = files.read(CURRENT_FILE, MAX_SNAPSHOT_BYTES);
                Snapshot current = decodeSnapshot(currentBytes, nowMillis);
                rewrite = !baseline.token.exactlyEquals(current.token)
                        || !MessageDigest.isEqual(baselineBytes, currentBytes);
            } catch (IOException ignored) {
                rewrite = true;
            }
        }

        if (rewrite) {
            writeAtomic(CURRENT_TEMP_FILE, CURRENT_FILE, baselineBytes);
        }

        byte[] stable = files.read(CURRENT_FILE, MAX_SNAPSHOT_BYTES);

        if (!MessageDigest.isEqual(stable, baselineBytes)) {
            throw new IOException("Plugin reputation current mirror mismatch");
        }

        decodeSnapshot(stable, nowMillis);
    }

    private void writeAtomic(String temporary, String target, byte[] value) throws IOException {
        files.write(temporary, value);
        files.rename(temporary, target);
        files.syncDirectory();
    }

    private boolean matchesFile(String name, byte[] expected) throws IOException {
        if (!files.exists(name)) {
            return false;
        }

        try {
            return MessageDigest.isEqual(files.read(name, MAX_SNAPSHOT_BYTES), expected);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void cleanupStableArtifacts() throws IOException {
        cleanupIfPresent(BASELINE_TEMP_FILE);
        cleanupIfPresent(CURRENT_TEMP_FILE);
        cleanupIfPresent(TRANSACTION_TEMP_FILE);
        cleanupIfPresent(BASELINE_BACKUP_FILE);
        cleanupIfPresent(CURRENT_BACKUP_FILE);
    }

    private void cleanupTemporaryArtifacts() throws IOException {
        cleanupIfPresent(BASELINE_TEMP_FILE);
        cleanupIfPresent(CURRENT_TEMP_FILE);
        cleanupIfPresent(TRANSACTION_TEMP_FILE);
    }

    private void cleanupIfPresent(String name) throws IOException {
        if (files.exists(name)) {
            files.delete(name);
            files.syncDirectory();
        }
    }

    private void validateInitializationArtifacts(byte[] afterSnapshot) throws IOException {
        for (String name : files.list()) {
            if (TRANSACTION_FILE.equals(name) || isTemporary(name)) {
                continue;
            }
            if (LOCK_FILE.equals(name)) {
                continue;
            }
            if (CURRENT_FILE.equals(name)
                    && MessageDigest.isEqual(
                            files.read(CURRENT_FILE, MAX_SNAPSHOT_BYTES),
                            afterSnapshot)) {
                continue;
            }

            throw new IOException("Plugin reputation initialization state is ambiguous");
        }
    }

    private static boolean isTemporary(String name) {
        return BASELINE_TEMP_FILE.equals(name)
                || CURRENT_TEMP_FILE.equals(name)
                || TRANSACTION_TEMP_FILE.equals(name);
    }

    private byte[] encodeSnapshot(byte[] storeId, PluginReputationCache cache) throws IOException {
        byte[] cacheBytes = cache.encode();
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.write(storeId);
            writeAuthority(output, authorityKeyId);
            output.writeLong(cache.generation());
            output.writeInt(cacheBytes.length);
            output.write(cacheBytes);
        }

        return finish(payloadBytes.toByteArray(), MAX_SNAPSHOT_BYTES);
    }

    private Snapshot decodeSnapshot(byte[] encoded, long nowMillis) throws IOException {
        byte[] payload = verify(encoded, MAX_SNAPSHOT_BYTES, 4 + 4 + STORE_ID_BYTES
                + 4 + AUTHORITY_BYTES + 8 + 4);

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != SNAPSHOT_MAGIC) {
                throw new IOException("Invalid plugin reputation store header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin reputation store version");
            }

            byte[] storeId = new byte[STORE_ID_BYTES];
            input.readFully(storeId);
            String authority = readAuthority(input);
            long generation = input.readLong();
            int cacheLength = input.readInt();

            if (!authorityKeyId.equals(authority)
                    || generation < 0
                    || cacheLength <= 0
                    || cacheLength > PluginReputationCache.MAX_ENCODED_BYTES) {
                throw new IOException("Invalid plugin reputation store identity");
            }

            byte[] cacheBytes = new byte[cacheLength];
            input.readFully(cacheBytes);

            if (input.read() != -1) {
                throw new IOException("Trailing plugin reputation store data");
            }

            PluginReputationCache cache = PluginReputationCache.decode(
                    cacheBytes,
                    authorityKeyId,
                    nowMillis);

            if (cache.generation() != generation) {
                throw new IOException("Plugin reputation store generation mismatch");
            }

            return new Snapshot(cache, new Token(
                    storeId,
                    generation,
                    sha256(encoded),
                    cacheBytes));
        } catch (EOFException error) {
            throw new IOException("Truncated plugin reputation store snapshot", error);
        }
    }

    private static byte[] finish(byte[] payload, int maximumBytes) throws IOException {
        if (payload.length > maximumBytes - CHECKSUM_BYTES) {
            throw new IOException("Plugin reputation store data exceeds size limit");
        }

        byte[] result = Arrays.copyOf(payload, payload.length + CHECKSUM_BYTES);
        byte[] checksum = sha256(payload);
        System.arraycopy(checksum, 0, result, payload.length, checksum.length);
        return result;
    }

    private static byte[] verify(byte[] encoded, int maximumBytes, int minimumPayloadBytes)
            throws IOException {
        if (encoded == null
                || encoded.length < minimumPayloadBytes + CHECKSUM_BYTES
                || encoded.length > maximumBytes) {
            throw new IOException("Invalid plugin reputation store data size");
        }

        int payloadLength = encoded.length - CHECKSUM_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] checksum = Arrays.copyOfRange(encoded, payloadLength, encoded.length);

        if (!MessageDigest.isEqual(checksum, sha256(payload))) {
            throw new IOException("Invalid plugin reputation store checksum");
        }

        return payload;
    }

    private static void writeAuthority(DataOutputStream output, String authority) throws IOException {
        byte[] encoded = authority.getBytes(StandardCharsets.US_ASCII);

        if (encoded.length != AUTHORITY_BYTES) {
            throw new IOException("Invalid plugin reputation authority identity");
        }

        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readAuthority(DataInputStream input) throws IOException {
        int length = input.readInt();

        if (length != AUTHORITY_BYTES) {
            throw new IOException("Invalid plugin reputation authority length");
        }

        byte[] encoded = new byte[length];
        input.readFully(encoded);
        String authority = new String(encoded, StandardCharsets.US_ASCII);

        if (!PluginReputationResponse.isKeyId(authority)) {
            throw new IOException("Invalid plugin reputation authority identity");
        }

        return authority;
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void validateNow(long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin reputation store time");
        }
    }

    interface FileOperations {
        void validateRoot() throws IOException;

        LockHandle lock() throws IOException;

        String[] list() throws IOException;

        boolean exists(String name) throws IOException;

        byte[] read(String name, int maximumBytes) throws IOException;

        void write(String name, byte[] value) throws IOException;

        void rename(String source, String target) throws IOException;

        void delete(String name) throws IOException;

        void syncDirectory() throws IOException;

        byte[] randomBytes(int count) throws IOException;
    }

    interface LockHandle extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    private static final class Token {
        private final byte[] storeId;
        private final long generation;
        private final byte[] snapshotDigest;
        private final byte[] cacheBytes;

        private Token(
                byte[] storeId,
                long generation,
                byte[] snapshotDigest,
                byte[] cacheBytes) {
            this.storeId = storeId.clone();
            this.generation = generation;
            this.snapshotDigest = snapshotDigest.clone();
            this.cacheBytes = cacheBytes.clone();
        }

        private boolean exactlyEquals(Token other) {
            return generation == other.generation
                    && MessageDigest.isEqual(storeId, other.storeId)
                    && MessageDigest.isEqual(snapshotDigest, other.snapshotDigest)
                    && MessageDigest.isEqual(cacheBytes, other.cacheBytes);
        }
    }

    private static final class Transaction {
        private final byte[] storeId;
        private final String authorityKeyId;
        private final long beforeGeneration;
        private final byte[] beforeDigest;
        private final long validatedAtMillis;
        private final byte[] afterSnapshot;
        private final long afterGeneration;

        private Transaction(
                byte[] storeId,
                String authorityKeyId,
                long beforeGeneration,
                byte[] beforeDigest,
                long validatedAtMillis,
                byte[] afterSnapshot) throws IOException {
            if (validatedAtMillis < 0) {
                throw new IOException("Invalid plugin reputation transaction validation time");
            }

            this.storeId = storeId.clone();
            this.authorityKeyId = authorityKeyId;
            this.beforeGeneration = beforeGeneration;
            this.beforeDigest = beforeDigest.clone();
            this.validatedAtMillis = validatedAtMillis;
            this.afterSnapshot = afterSnapshot.clone();
            this.afterGeneration = readSnapshotGeneration(afterSnapshot);
        }

        private byte[] encode() throws IOException {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();

            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(TRANSACTION_MAGIC);
                output.writeInt(TRANSACTION_FORMAT_VERSION);
                output.write(storeId);
                writeAuthority(output, authorityKeyId);
                output.writeLong(beforeGeneration);
                output.write(beforeDigest);
                output.writeLong(validatedAtMillis);
                output.writeLong(afterGeneration);
                output.writeInt(afterSnapshot.length);
                output.write(afterSnapshot);
            }

            return finish(payloadBytes.toByteArray(), MAX_TRANSACTION_BYTES);
        }

        private static Transaction decode(byte[] encoded, String expectedAuthority)
                throws IOException {
            byte[] payload = verify(encoded, MAX_TRANSACTION_BYTES, 4 + 4 + STORE_ID_BYTES
                    + 4 + AUTHORITY_BYTES + 8 + CHECKSUM_BYTES + 8 + 8 + 4);

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != TRANSACTION_MAGIC) {
                    throw new IOException("Invalid plugin reputation transaction header");
                }
                if (input.readInt() != TRANSACTION_FORMAT_VERSION) {
                    throw new IOException("Unsupported plugin reputation transaction version");
                }

                byte[] storeId = new byte[STORE_ID_BYTES];
                input.readFully(storeId);
                String authority = readAuthority(input);
                long beforeGeneration = input.readLong();
                byte[] beforeDigest = new byte[CHECKSUM_BYTES];
                input.readFully(beforeDigest);
                long validatedAtMillis = input.readLong();
                long afterGeneration = input.readLong();
                int afterLength = input.readInt();

                if (!expectedAuthority.equals(authority)
                        || beforeGeneration < -1
                        || validatedAtMillis < 0
                        || afterGeneration < 0
                        || afterGeneration != beforeGeneration + 1
                        || beforeGeneration == -1
                                && !MessageDigest.isEqual(beforeDigest, ABSENT_DIGEST)
                        || afterLength <= 0
                        || afterLength > MAX_SNAPSHOT_BYTES) {
                    throw new IOException("Invalid plugin reputation transaction identity");
                }

                byte[] after = new byte[afterLength];
                input.readFully(after);

                if (input.read() != -1) {
                    throw new IOException("Trailing plugin reputation transaction data");
                }

                Transaction transaction = new Transaction(
                        storeId,
                        authority,
                        beforeGeneration,
                        beforeDigest,
                        validatedAtMillis,
                        after);

                if (transaction.afterGeneration != afterGeneration) {
                    throw new IOException("Plugin reputation transaction generation mismatch");
                }

                return transaction;
            } catch (EOFException error) {
                throw new IOException("Truncated plugin reputation transaction", error);
            }
        }

        private boolean matchesBefore(Token token) {
            return beforeGeneration == token.generation
                    && MessageDigest.isEqual(storeId, token.storeId)
                    && MessageDigest.isEqual(beforeDigest, token.snapshotDigest);
        }

        private static long readSnapshotGeneration(byte[] encoded) throws IOException {
            byte[] payload = verify(encoded, MAX_SNAPSHOT_BYTES, 4 + 4 + STORE_ID_BYTES
                    + 4 + AUTHORITY_BYTES + 8 + 4);

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                input.skipBytes(4 + 4 + STORE_ID_BYTES);
                readAuthority(input);
                return input.readLong();
            }
        }
    }

    private static final class AndroidFileOperations implements FileOperations {
        private final File root;
        private final SecureRandom random = new SecureRandom();

        private AndroidFileOperations(File root) {
            this.root = root;
        }

        @Override
        public void validateRoot() throws IOException {
            try {
                StructStat stat = Os.lstat(root.getAbsolutePath());

                if (!OsConstants.S_ISDIR(stat.st_mode)) {
                    throw new IOException("Plugin reputation root is not a directory");
                }
            } catch (ErrnoException error) {
                throw new IOException("Unable to validate plugin reputation root", error);
            }
        }

        @Override
        public LockHandle lock() throws IOException {
            FileDescriptor descriptor = null;
            FileOutputStream stream = null;

            try {
                descriptor = Os.open(
                        file(LOCK_FILE).getAbsolutePath(),
                        OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_NOFOLLOW,
                        0600);
                StructStat stat = Os.fstat(descriptor);

                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw new IOException("Invalid plugin reputation lock file type");
                }

                stream = new FileOutputStream(descriptor);
                descriptor = null;
                FileChannel channel = stream.getChannel();
                FileLock fileLock = channel.lock();
                return new AndroidLockHandle(stream, fileLock);
            } catch (ErrnoException error) {
                close(descriptor);
                close(stream);
                throw new IOException("Unable to lock plugin reputation store", error);
            } catch (IOException | RuntimeException error) {
                close(descriptor);
                close(stream);
                throw error;
            }
        }

        @Override
        public String[] list() throws IOException {
            String[] names = root.list();

            if (names == null) {
                throw new IOException("Unable to list plugin reputation root");
            }

            return names;
        }

        @Override
        public boolean exists(String name) throws IOException {
            try {
                StructStat stat = Os.lstat(file(name).getAbsolutePath());

                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw new IOException("Invalid plugin reputation file type");
                }

                return true;
            } catch (ErrnoException error) {
                if (error.errno == OsConstants.ENOENT) {
                    return false;
                }

                throw new IOException("Unable to inspect plugin reputation file", error);
            }
        }

        @Override
        public byte[] read(String name, int maximumBytes) throws IOException {
            FileDescriptor descriptor = null;

            try {
                descriptor = Os.open(
                        file(name).getAbsolutePath(),
                        OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW,
                        0);
                StructStat stat = Os.fstat(descriptor);

                if (!OsConstants.S_ISREG(stat.st_mode)
                        || stat.st_size < 0
                        || stat.st_size > maximumBytes) {
                    throw new IOException("Invalid plugin reputation file size or type");
                }

                try (FileInputStream input = new FileInputStream(descriptor);
                     ByteArrayOutputStream output = new ByteArrayOutputStream((int) stat.st_size)) {
                    descriptor = null;
                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);

                        if (output.size() > maximumBytes) {
                            throw new IOException("Plugin reputation file exceeds size limit");
                        }
                    }

                    return output.toByteArray();
                }
            } catch (ErrnoException error) {
                throw new IOException("Unable to read plugin reputation file", error);
            } finally {
                close(descriptor);
            }
        }

        @Override
        public void write(String name, byte[] value) throws IOException {
            FileDescriptor descriptor = null;

            try {
                descriptor = Os.open(
                        file(name).getAbsolutePath(),
                        OsConstants.O_WRONLY
                                | OsConstants.O_CREAT
                                | OsConstants.O_TRUNC
                                | OsConstants.O_NOFOLLOW,
                        0600);
                StructStat stat = Os.fstat(descriptor);

                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw new IOException("Invalid plugin reputation temporary file type");
                }

                try (FileOutputStream output = new FileOutputStream(descriptor)) {
                    descriptor = null;
                    output.write(value);
                    output.flush();
                    output.getFD().sync();
                }
            } catch (ErrnoException error) {
                throw new IOException("Unable to write plugin reputation file", error);
            } finally {
                close(descriptor);
            }
        }

        @Override
        public void rename(String source, String target) throws IOException {
            try {
                Os.rename(file(source).getAbsolutePath(), file(target).getAbsolutePath());
            } catch (ErrnoException error) {
                throw new IOException("Unable to publish plugin reputation file", error);
            }
        }

        @Override
        public void delete(String name) throws IOException {
            if (!exists(name)) {
                return;
            }

            try {
                Os.remove(file(name).getAbsolutePath());
            } catch (ErrnoException error) {
                throw new IOException("Unable to remove plugin reputation file", error);
            }
        }

        @Override
        public void syncDirectory() throws IOException {
            FileDescriptor descriptor = null;

            try {
                descriptor = Os.open(root.getAbsolutePath(), OsConstants.O_RDONLY, 0);
                StructStat stat = Os.fstat(descriptor);

                if (!OsConstants.S_ISDIR(stat.st_mode)) {
                    throw new IOException("Invalid plugin reputation directory type");
                }

                Os.fsync(descriptor);
            } catch (ErrnoException error) {
                throw new IOException("Unable to sync plugin reputation directory", error);
            } finally {
                close(descriptor);
            }
        }

        @Override
        public byte[] randomBytes(int count) {
            byte[] value = new byte[count];
            random.nextBytes(value);
            return value;
        }

        private File file(String name) throws IOException {
            if (!BASELINE_FILE.equals(name)
                    && !CURRENT_FILE.equals(name)
                    && !TRANSACTION_FILE.equals(name)
                    && !BASELINE_TEMP_FILE.equals(name)
                    && !CURRENT_TEMP_FILE.equals(name)
                    && !TRANSACTION_TEMP_FILE.equals(name)
                    && !BASELINE_BACKUP_FILE.equals(name)
                    && !CURRENT_BACKUP_FILE.equals(name)
                    && !LOCK_FILE.equals(name)) {
                throw new IOException("Invalid plugin reputation file name");
            }

            return new File(root, name);
        }

        private static void close(FileDescriptor descriptor) throws IOException {
            if (descriptor == null) {
                return;
            }

            try {
                Os.close(descriptor);
            } catch (ErrnoException error) {
                throw new IOException("Unable to close plugin reputation file", error);
            }
        }

        private static void close(FileOutputStream stream) throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private static final class AndroidLockHandle implements LockHandle {
        private FileOutputStream stream;
        private FileLock lock;

        private AndroidLockHandle(FileOutputStream stream, FileLock lock) {
            this.stream = stream;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            FileLock currentLock = lock;
            FileOutputStream currentStream = stream;
            lock = null;
            stream = null;
            IOException failure = null;

            if (currentLock != null) {
                try {
                    currentLock.release();
                } catch (IOException error) {
                    failure = error;
                }
            }
            if (currentStream != null) {
                try {
                    currentStream.close();
                } catch (IOException error) {
                    if (failure == null) {
                        failure = error;
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
