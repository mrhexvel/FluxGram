package org.telegram.messenger.plugins.reputation;

import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class PluginReputationStoreCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        codecAndInitialization();
        updatesAndOptimisticToken();
        repairAndFailClosed();
        transactionRecovery();
        crashRecovery();
        filesystemRejection();
        interProcessLockBoundary();
        concurrentInstancesAndCopies();
    }

    private static void codecAndInitialization() throws Exception {
        Path root = Files.createTempDirectory("reputation-store-codec-");
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        HostFiles files = new HostFiles(root.toFile());
        PluginReputationStore store = store(root, authorityId, files);
        PluginReputationStore.Snapshot snapshot = store.load(100);

        require(snapshot.generation() == 0);
        require(snapshot.cache.size() == 0);
        byte[] baseline = files.read(PluginReputationStore.BASELINE_FILE, PluginReputationStore.MAX_SNAPSHOT_BYTES);
        byte[] current = files.read(PluginReputationStore.CURRENT_FILE, PluginReputationStore.MAX_SNAPSHOT_BYTES);
        require(Arrays.equals(baseline, current));
        require(readInt(baseline, 0) == 0x46475253);
        require(readInt(baseline, 4) == PluginReputationStore.FORMAT_VERSION);
        require(readLong(baseline, 113) == 0);
        verifyChecksum(baseline);
        require(!files.exists(PluginReputationStore.TRANSACTION_FILE));

        PluginReputationStore second = store(root, authorityId, new HostFiles(root.toFile()));
        require(second.load(100).generation() == 0);
        expectIo(() -> store(root, keyId(keyPair()), new HostFiles(root.toFile())).load(100));

        Path badMagicRoot = copyRoot(root, "reputation-store-magic-");
        mutate(badMagicRoot.resolve(PluginReputationStore.BASELINE_FILE), value -> value[0] ^= 1);
        expectIo(() -> store(badMagicRoot, authorityId, new HostFiles(badMagicRoot.toFile())).load(100));

        Path badVersionRoot = copyRoot(root, "reputation-store-version-");
        mutate(badVersionRoot.resolve(PluginReputationStore.BASELINE_FILE), value -> {
            value[7] = 2;
            refreshChecksum(value);
        });
        expectIo(() -> store(badVersionRoot, authorityId, new HostFiles(badVersionRoot.toFile())).load(100));

        Path badChecksumRoot = copyRoot(root, "reputation-store-checksum-");
        mutate(badChecksumRoot.resolve(PluginReputationStore.BASELINE_FILE), value -> value[value.length - 1] ^= 1);
        expectIo(() -> store(badChecksumRoot, authorityId, new HostFiles(badChecksumRoot.toFile())).load(100));

        Path badGenerationRoot = copyRoot(root, "reputation-store-generation-");
        mutate(badGenerationRoot.resolve(PluginReputationStore.BASELINE_FILE), value -> {
            value[120] = 1;
            refreshChecksum(value);
        });
        expectIo(() -> store(badGenerationRoot, authorityId, new HostFiles(badGenerationRoot.toFile())).load(100));

        Path badLengthRoot = copyRoot(root, "reputation-store-length-");
        mutate(badLengthRoot.resolve(PluginReputationStore.BASELINE_FILE), value -> {
            writeInt(value, 121, PluginReputationCache.MAX_ENCODED_BYTES + 1);
            refreshChecksum(value);
        });
        expectIo(() -> store(badLengthRoot, authorityId, new HostFiles(badLengthRoot.toFile())).load(100));

        Path oversizedRoot = copyRoot(root, "reputation-store-oversized-");
        write(oversizedRoot.resolve(PluginReputationStore.BASELINE_FILE),
                new byte[PluginReputationStore.MAX_SNAPSHOT_BYTES + 1]);
        expectIo(() -> store(oversizedRoot, authorityId, new HostFiles(oversizedRoot.toFile())).load(100));

        Path unknownRoot = Files.createTempDirectory("reputation-store-unknown-");
        write(unknownRoot.resolve("unknown"), new byte[] {1});
        expectIo(() -> store(unknownRoot, authorityId, new HostFiles(unknownRoot.toFile())).load(100));
    }

    private static void updatesAndOptimisticToken() throws Exception {
        Path root = Files.createTempDirectory("reputation-store-update-");
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        CountingFiles files = new CountingFiles(root.toFile());
        PluginReputationStore store = store(root, authorityId, files);
        PluginReputationStore.Snapshot initial = store.load(150);
        Signed clear = signed(authority, response(identity(1), null, 1, 100, 300, false));

        files.reset();
        PluginReputationStore.UpdateResult accepted = store.update(
                initial,
                clear.response,
                clear.envelope,
                150);
        require(accepted.status == PluginReputationCache.UpdateStatus.ACCEPTED);
        require(accepted.persisted);
        require(accepted.snapshot.generation() == 1);
        require(files.mutations > 0);
        require(accepted.snapshot.cache.currentResponseBytes(identity(1), null) != null);

        files.reset();
        PluginReputationStore.UpdateResult unchanged = store.update(
                accepted.snapshot,
                clear.response,
                clear.envelope,
                150);
        require(unchanged.status == PluginReputationCache.UpdateStatus.UNCHANGED);
        require(!unchanged.persisted && files.mutations == 0);

        Signed old = signed(authority, response(identity(1), null, 0, 100, 300, false));
        PluginReputationStore.UpdateResult rejected = store.update(
                accepted.snapshot,
                old.response,
                old.envelope,
                150);
        require(rejected.status == PluginReputationCache.UpdateStatus.REJECTED_OLD);
        require(!rejected.persisted && files.mutations == 0);

        expectIo(() -> store.update(initial, clear.response, clear.envelope, 150));

        Path otherRoot = Files.createTempDirectory("reputation-store-token-");
        PluginReputationStore other = store(otherRoot, authorityId, new HostFiles(otherRoot.toFile()));
        PluginReputationStore.Snapshot otherSnapshot = other.load(150);
        Signed otherRecord = signed(authority, response(identity(2), null, 1, 100, 300, false));
        expectIo(() -> store.update(
                otherSnapshot,
                otherRecord.response,
                otherRecord.envelope,
                150));
    }

    private static void repairAndFailClosed() throws Exception {
        Path root = Files.createTempDirectory("reputation-store-repair-");
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        HostFiles files = new HostFiles(root.toFile());
        PluginReputationStore store = store(root, authorityId, files);
        PluginReputationStore.Snapshot initial = store.load(150);
        Signed blocked = signed(authority, response(identity(3), null, 1, 100, 300, true));
        PluginReputationStore.Snapshot updated = store.update(
                initial,
                blocked.response,
                blocked.envelope,
                150).snapshot;
        byte[] baseline = files.read(PluginReputationStore.BASELINE_FILE, PluginReputationStore.MAX_SNAPSHOT_BYTES);

        files.delete(PluginReputationStore.CURRENT_FILE);
        require(store.load(150).generation() == updated.generation());
        require(Arrays.equals(
                baseline,
                files.read(PluginReputationStore.CURRENT_FILE, PluginReputationStore.MAX_SNAPSHOT_BYTES)));

        write(root.resolve(PluginReputationStore.CURRENT_FILE), new byte[] {1, 2, 3});
        require(store.load(150).generation() == updated.generation());
        require(Arrays.equals(
                baseline,
                files.read(PluginReputationStore.CURRENT_FILE, PluginReputationStore.MAX_SNAPSHOT_BYTES)));

        write(root.resolve(PluginReputationStore.BASELINE_BACKUP_FILE), new byte[] {9});
        require(store.load(150).generation() == updated.generation());
        require(!files.exists(PluginReputationStore.BASELINE_BACKUP_FILE));

        Path missing = copyRoot(root, "reputation-store-missing-baseline-");
        Files.delete(missing.resolve(PluginReputationStore.BASELINE_FILE));
        expectIo(() -> store(missing, authorityId, new HostFiles(missing.toFile())).load(150));

        Path corrupt = copyRoot(root, "reputation-store-corrupt-baseline-");
        mutate(corrupt.resolve(PluginReputationStore.BASELINE_FILE), value -> value[0] ^= 1);
        expectIo(() -> store(corrupt, authorityId, new HostFiles(corrupt.toFile())).load(150));
    }

    private static void transactionRecovery() throws Exception {
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        Path base = Files.createTempDirectory("reputation-store-transaction-");
        PluginReputationStore baseStore = store(base, authorityId, new HostFiles(base.toFile()));
        PluginReputationStore.Snapshot initial = baseStore.load(150);
        Signed block = signed(authority, response(identity(4), null, 1, 100, 300, true));

        Path corruptRoot = copyRoot(base, "reputation-store-corrupt-transaction-");
        FailAfterFiles corruptFiles = new FailAfterFiles(corruptRoot.toFile(), 2);
        PluginReputationStore corruptStore = store(corruptRoot, authorityId, corruptFiles);
        expectIo(() -> corruptStore.update(
                corruptStore.load(150),
                block.response,
                block.envelope,
                150));
        require(new HostFiles(corruptRoot.toFile()).exists(PluginReputationStore.TRANSACTION_FILE));
        Path transactionSource = copyRoot(corruptRoot, "reputation-store-transaction-source-");
        mutate(corruptRoot.resolve(PluginReputationStore.TRANSACTION_FILE), value -> {
            value[0] ^= 1;
            refreshChecksum(value);
        });
        expectIo(() -> store(corruptRoot, authorityId, new HostFiles(corruptRoot.toFile())).load(150));

        Path badVersion = copyRoot(transactionSource, "reputation-store-transaction-version-");
        mutate(badVersion.resolve(PluginReputationStore.TRANSACTION_FILE), value -> {
            value[7] = 2;
            refreshChecksum(value);
        });
        expectIo(() -> store(badVersion, authorityId, new HostFiles(badVersion.toFile())).load(150));

        Path badChecksum = copyRoot(transactionSource, "reputation-store-transaction-checksum-");
        mutate(badChecksum.resolve(PluginReputationStore.TRANSACTION_FILE),
                value -> value[value.length - 1] ^= 1);
        expectIo(() -> store(badChecksum, authorityId, new HostFiles(badChecksum.toFile())).load(150));

        Path badBounds = copyRoot(transactionSource, "reputation-store-transaction-bounds-");
        mutate(badBounds.resolve(PluginReputationStore.TRANSACTION_FILE), value -> {
            writeInt(value, 169, PluginReputationStore.MAX_SNAPSHOT_BYTES + 1);
            refreshChecksum(value);
        });
        expectIo(() -> store(badBounds, authorityId, new HostFiles(badBounds.toFile())).load(150));

        Path badValidatedAt = copyRoot(transactionSource, "reputation-store-transaction-time-");
        mutate(badValidatedAt.resolve(PluginReputationStore.TRANSACTION_FILE), value -> {
            value[153] = (byte) 0x80;
            refreshChecksum(value);
        });
        expectIo(() -> store(
                badValidatedAt,
                authorityId,
                new HostFiles(badValidatedAt.toFile())).load(150));

        Path oversized = copyRoot(transactionSource, "reputation-store-transaction-oversized-");
        write(oversized.resolve(PluginReputationStore.TRANSACTION_FILE),
                new byte[PluginReputationStore.MAX_TRANSACTION_BYTES + 1]);
        expectIo(() -> store(oversized, authorityId, new HostFiles(oversized.toFile())).load(150));

        Path staleRoot = copyRoot(base, "reputation-store-stale-transaction-");
        FailOnDeleteFiles staleFiles = new FailOnDeleteFiles(staleRoot.toFile());
        PluginReputationStore staleStore = store(staleRoot, authorityId, staleFiles);
        expectIo(() -> staleStore.update(
                staleStore.load(150),
                block.response,
                block.envelope,
                150));
        HostFiles stableFiles = new HostFiles(staleRoot.toFile());
        require(stableFiles.exists(PluginReputationStore.TRANSACTION_FILE));
        PluginReputationStore.Snapshot recovered = store(staleRoot, authorityId, stableFiles).load(150);
        require(recovered.generation() == initial.generation() + 1);
        require(recovered.cache.lookup(identity(4), null, authorityId, 150).decision
                == PluginReputationPolicy.Decision.BLOCK);
        require(!stableFiles.exists(PluginReputationStore.TRANSACTION_FILE));

        Signed clear = signed(authority, response(identity(4), null, 2, 150, 350, false));
        PluginReputationStore.Snapshot cleared = store(staleRoot, authorityId, stableFiles).update(
                recovered,
                clear.response,
                clear.envelope,
                200).snapshot;
        require(cleared.cache.blockingAnchorResponseBytes(identity(4), null) != null);

        Path rollbackRoot = copyRoot(base, "reputation-store-clock-rollback-");
        FailAfterFiles rollbackFiles = new FailAfterFiles(rollbackRoot.toFile(), 3);
        PluginReputationStore rollbackStore = store(rollbackRoot, authorityId, rollbackFiles);
        expectIo(() -> rollbackStore.update(
                rollbackStore.load(150),
                block.response,
                block.envelope,
                150));
        HostFiles rollbackRecoveryFiles = new HostFiles(rollbackRoot.toFile());
        require(rollbackRecoveryFiles.exists(PluginReputationStore.TRANSACTION_FILE));
        PluginReputationStore recoveredAfterRollback = store(
                rollbackRoot,
                authorityId,
                rollbackRecoveryFiles);
        PluginReputationStore.Snapshot rollbackSnapshot = recoveredAfterRollback.load(50);
        require(rollbackSnapshot.generation() == 1);
        require(rollbackSnapshot.cache.lookup(identity(4), null, authorityId, 50).decision
                == PluginReputationPolicy.Decision.BLOCK);
        require(!rollbackRecoveryFiles.exists(PluginReputationStore.TRANSACTION_FILE));
        require(recoveredAfterRollback.load(50).generation() == 1);
    }

    private static void crashRecovery() throws Exception {
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        Signed record = signed(authority, response(identity(5), null, 1, 100, 300, true));

        Path countInit = Files.createTempDirectory("reputation-store-count-init-");
        CountingFiles initCounter = new CountingFiles(countInit.toFile());
        store(countInit, authorityId, initCounter).load(150);
        int initMutations = initCounter.mutations;
        require(initMutations > 0);

        for (int failure = 1; failure <= initMutations; failure++) {
            Path root = Files.createTempDirectory("reputation-store-crash-init-");
            PluginReputationStore crashing = store(
                    root,
                    authorityId,
                    new FailAfterFiles(root.toFile(), failure));
            expectIo(() -> crashing.load(150));
            PluginReputationStore recovered = store(root, authorityId, new HostFiles(root.toFile()));
            require(recovered.load(150).generation() == 0);
            require(recovered.load(150).generation() == 0);
        }

        Path base = Files.createTempDirectory("reputation-store-crash-base-");
        PluginReputationStore baseStore = store(base, authorityId, new HostFiles(base.toFile()));
        baseStore.load(150);
        Path countUpdate = copyRoot(base, "reputation-store-count-update-");
        CountingFiles updateCounter = new CountingFiles(countUpdate.toFile());
        PluginReputationStore countingStore = store(countUpdate, authorityId, updateCounter);
        PluginReputationStore.Snapshot expected = countingStore.load(150);
        updateCounter.reset();
        countingStore.update(expected, record.response, record.envelope, 150);
        int updateMutations = updateCounter.mutations;
        require(updateMutations > 0);

        for (int failure = 1; failure <= updateMutations; failure++) {
            Path root = copyRoot(base, "reputation-store-crash-update-");
            PluginReputationStore crashing = store(
                    root,
                    authorityId,
                    new FailAfterFiles(root.toFile(), failure));
            PluginReputationStore.Snapshot before = crashing.load(150);
            expectIo(() -> crashing.update(before, record.response, record.envelope, 150));
            boolean durableTransaction = new HostFiles(root.toFile()).exists(
                    PluginReputationStore.TRANSACTION_FILE);
            PluginReputationStore recovered = store(root, authorityId, new HostFiles(root.toFile()));
            PluginReputationStore.Snapshot after = recovered.load(150);
            require(after.generation() == 0 || after.generation() == 1);

            if (durableTransaction) {
                require(after.generation() == 1);
            }

            if (after.generation() == 1) {
                require(after.cache.lookup(identity(5), null, authorityId, 150).decision
                        == PluginReputationPolicy.Decision.BLOCK);
            }

            require(recovered.load(150).generation() == after.generation());
        }
    }

    private static void filesystemRejection() throws Exception {
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);

        Path directoryRoot = Files.createTempDirectory("reputation-store-directory-");
        Files.createDirectory(directoryRoot.resolve(PluginReputationStore.BASELINE_FILE));
        expectIo(() -> store(directoryRoot, authorityId, new HostFiles(directoryRoot.toFile())).load(150));

        Path symlinkRoot = Files.createTempDirectory("reputation-store-symlink-");
        Path target = Files.createTempFile("reputation-store-target-", ".bin");

        try {
            Files.createSymbolicLink(symlinkRoot.resolve(PluginReputationStore.BASELINE_FILE), target);
            expectIo(() -> store(symlinkRoot, authorityId, new HostFiles(symlinkRoot.toFile())).load(150));
        } catch (UnsupportedOperationException | IOException ignored) {
        }

        Path lockSymlinkRoot = Files.createTempDirectory("reputation-store-lock-symlink-");

        try {
            Files.createSymbolicLink(
                    lockSymlinkRoot.resolve(PluginReputationStore.LOCK_FILE),
                    target);
            expectIo(() -> store(
                    lockSymlinkRoot,
                    authorityId,
                    new HostFiles(lockSymlinkRoot.toFile())).load(150));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void concurrentInstancesAndCopies() throws Exception {
        Path root = Files.createTempDirectory("reputation-store-concurrent-");
        KeyPair authority = keyPair();
        String authorityId = keyId(authority);
        PluginReputationStore first = store(root, authorityId, new HostFiles(root.toFile()));
        PluginReputationStore second = store(root, authorityId, new HostFiles(root.toFile()));
        PluginReputationStore.Snapshot firstSnapshot = first.load(150);
        PluginReputationStore.Snapshot secondSnapshot = second.load(150);
        Signed firstRecord = signed(authority, response(identity(6), null, 1, 100, 300, false));
        Signed secondRecord = signed(authority, response(identity(7), null, 1, 100, 300, false));
        PluginReputationStore.Snapshot updated = first.update(
                firstSnapshot,
                firstRecord.response,
                firstRecord.envelope,
                150).snapshot;
        expectIo(() -> second.update(
                secondSnapshot,
                secondRecord.response,
                secondRecord.envelope,
                150));

        byte[] responseCopy = updated.cache.currentResponseBytes(identity(6), null);
        responseCopy[0] ^= 1;
        require(updated.cache.currentResponseBytes(identity(6), null)[0] != responseCopy[0]);
    }

    private static void interProcessLockBoundary() throws Exception {
        Path root = Files.createTempDirectory("reputation-store-lock-");
        HostFiles first = new HostFiles(root.toFile());
        HostFiles second = new HostFiles(root.toFile());
        PluginReputationStore.LockHandle held = first.lock();
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean entered = new AtomicBoolean();
        List<Throwable> failures = new ArrayList<>();
        Thread contender = new Thread(() -> {
            attempted.countDown();

            try (PluginReputationStore.LockHandle ignored = second.lock()) {
                entered.set(true);
            } catch (Throwable error) {
                failures.add(error);
            } finally {
                completed.countDown();
            }
        });
        contender.start();
        require(attempted.await(5, TimeUnit.SECONDS));
        Thread.sleep(100);
        require(!entered.get());
        held.close();
        require(completed.await(5, TimeUnit.SECONDS));
        require(entered.get());
        require(failures.isEmpty());
    }

    private static PluginReputationStore store(
            Path root,
            String authority,
            PluginReputationStore.FileOperations files) throws IOException {
        return new PluginReputationStore(root.toFile(), authority, files);
    }

    private static PluginReputationResponse response(
            String archive,
            String developer,
            long revision,
            long issuedAt,
            long expiresAt,
            boolean blocked) {
        return new PluginReputationResponse(
                archive,
                developer,
                revision,
                issuedAt,
                expiresAt,
                blocked
                        ? PluginReputationResponse.ArchiveStatus.MALICIOUS
                        : PluginReputationResponse.ArchiveStatus.CLEAR,
                developer == null
                        ? PluginReputationResponse.KeyStatus.NOT_APPLICABLE
                        : blocked
                                ? PluginReputationResponse.KeyStatus.REVOKED
                                : PluginReputationResponse.KeyStatus.CLEAR);
    }

    private static Signed signed(KeyPair authority, PluginReputationResponse response) throws Exception {
        byte[] responseBytes = response.encode();
        byte[] message = PluginReputationVerifier.canonicalMessage(
                response,
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                authority.getPublic().getEncoded());
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                authority.getPublic().getEncoded(),
                sign(authority, message));
        return new Signed(responseBytes, envelope.encode());
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String keyId(KeyPair key) throws Exception {
        return "p256:" + hex(MessageDigest.getInstance("SHA-256").digest(key.getPublic().getEncoded()));
    }

    private static byte[] sign(KeyPair key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return lowS(signer.sign());
    }

    private static byte[] lowS(byte[] signature) {
        int rLength = signature[3] & 255;
        int rStart = 4;
        int sOffset = rStart + rLength;
        int sLength = signature[sOffset + 1] & 255;
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, rStart, rStart + rLength));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, sOffset + 2, sOffset + 2 + sLength));

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        byte[] rBytes = positiveInteger(r);
        byte[] sBytes = positiveInteger(s);
        byte[] result = new byte[6 + rBytes.length + sBytes.length];
        int offset = 0;
        result[offset++] = 0x30;
        result[offset++] = (byte) (result.length - 2);
        result[offset++] = 0x02;
        result[offset++] = (byte) rBytes.length;
        System.arraycopy(rBytes, 0, result, offset, rBytes.length);
        offset += rBytes.length;
        result[offset++] = 0x02;
        result[offset++] = (byte) sBytes.length;
        System.arraycopy(sBytes, 0, result, offset, sBytes.length);
        return result;
    }

    private static byte[] positiveInteger(BigInteger value) {
        byte[] encoded = value.toByteArray();

        if (encoded[0] == 0 && encoded.length > 1 && (encoded[1] & 128) == 0) {
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        }
        if ((encoded[0] & 128) != 0) {
            byte[] prefixed = new byte[encoded.length + 1];
            System.arraycopy(encoded, 0, prefixed, 1, encoded.length);
            return prefixed;
        }

        return encoded;
    }

    private static Path copyRoot(Path source, String prefix) throws IOException {
        Path target = Files.createTempDirectory(prefix);

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path file : stream) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }

        return target;
    }

    private static void mutate(Path file, Mutation mutation) throws Exception {
        byte[] value = Files.readAllBytes(file);
        mutation.apply(value);
        write(file, value);
    }

    private static void write(Path file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file.toFile())) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private static void verifyChecksum(byte[] value) throws Exception {
        int payloadLength = value.length - 32;
        require(MessageDigest.isEqual(
                Arrays.copyOfRange(value, payloadLength, value.length),
                MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(value, payloadLength))));
    }

    private static void refreshChecksum(byte[] value) throws Exception {
        int payloadLength = value.length - 32;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(value, payloadLength));
        System.arraycopy(digest, 0, value, payloadLength, digest.length);
    }

    private static int readInt(byte[] value, int offset) {
        return (value[offset] & 255) << 24
                | (value[offset + 1] & 255) << 16
                | (value[offset + 2] & 255) << 8
                | value[offset + 3] & 255;
    }

    private static long readLong(byte[] value, int offset) {
        return (long) readInt(value, offset) << 32 | readInt(value, offset + 4) & 0xffffffffL;
    }

    private static void writeInt(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    private static String identity(int value) {
        return String.format("%064x", value);
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];

        for (int i = 0; i < value.length; i++) {
            result[i * 2] = alphabet[(value[i] >>> 4) & 15];
            result[i * 2 + 1] = alphabet[value[i] & 15];
        }

        return new String(result);
    }

    private static void expectIo(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
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

    private interface Mutation {
        void apply(byte[] value) throws Exception;
    }

    private static final class Signed {
        private final byte[] response;
        private final byte[] envelope;

        private Signed(byte[] response, byte[] envelope) {
            this.response = response;
            this.envelope = envelope;
        }
    }

    private static class HostFiles implements PluginReputationStore.FileOperations {
        private static final ConcurrentHashMap<String, ReentrantLock> FILE_LOCKS =
                new ConcurrentHashMap<>();

        protected final File root;

        private HostFiles(File root) {
            this.root = root;
        }

        @Override
        public void validateRoot() throws IOException {
            if (Files.isSymbolicLink(root.toPath()) || !Files.isDirectory(root.toPath())) {
                throw new IOException("Invalid root");
            }
        }

        @Override
        public PluginReputationStore.LockHandle lock() throws IOException {
            Path lockPath = path(PluginReputationStore.LOCK_FILE);

            if (Files.exists(lockPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(lockPath)
                        || !Files.isRegularFile(
                                lockPath,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid lock file");
                }
            } else {
                try (FileOutputStream output = new FileOutputStream(lockPath.toFile())) {
                    output.getFD().sync();
                }
            }

            String identity = root.getCanonicalPath();
            ReentrantLock fileLock = FILE_LOCKS.computeIfAbsent(
                    identity,
                    ignored -> new ReentrantLock());
            fileLock.lock();
            return new HostLockHandle(fileLock);
        }

        @Override
        public String[] list() throws IOException {
            String[] names = root.list();

            if (names == null) {
                throw new IOException("Unable to list root");
            }

            return names;
        }

        @Override
        public boolean exists(String name) throws IOException {
            Path path = path(name);

            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid file type");
            }

            return true;
        }

        @Override
        public byte[] read(String name, int maximumBytes) throws IOException {
            Path path = path(name);

            if (!exists(name)) {
                throw new IOException("Missing file");
            }
            if (Files.size(path) > maximumBytes) {
                throw new IOException("Oversized file");
            }

            byte[] value = Files.readAllBytes(path);

            if (value.length > maximumBytes) {
                throw new IOException("Oversized file");
            }

            return value;
        }

        @Override
        public void write(String name, byte[] value) throws IOException {
            Path path = path(name);

            if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(path)
                            || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("Invalid write target");
            }

            try (FileOutputStream output = new FileOutputStream(path.toFile())) {
                output.write(value);
                output.getFD().sync();
            }

            mutated();
        }

        @Override
        public void rename(String source, String target) throws IOException {
            Path sourcePath = path(source);
            Path targetPath = path(target);

            if (Files.isSymbolicLink(sourcePath)
                    || !Files.isRegularFile(sourcePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid rename source");
            }

            Files.move(
                    sourcePath,
                    targetPath,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            mutated();
        }

        @Override
        public void delete(String name) throws IOException {
            Path path = path(name);

            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid delete target");
            }

            Files.delete(path);
            mutated();
        }

        @Override
        public void syncDirectory() throws IOException {
            mutated();
        }

        @Override
        public byte[] randomBytes(int count) throws IOException {
            byte[] value = new byte[count];
            byte[] seed;

            try {
                seed = MessageDigest.getInstance("SHA-256").digest(
                        root.getCanonicalPath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.security.NoSuchAlgorithmException error) {
                throw new IOException(error);
            }

            for (int i = 0; i < count; i++) {
                value[i] = seed[i % seed.length];
            }

            return value;
        }

        protected void mutated() throws IOException {
        }

        private Path path(String name) throws IOException {
            if (name == null || name.contains("/") || name.contains("\\")) {
                throw new IOException("Invalid name");
            }

            return root.toPath().resolve(name);
        }
    }

    private static final class HostLockHandle implements PluginReputationStore.LockHandle {
        private ReentrantLock lock;

        private HostLockHandle(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            ReentrantLock current = lock;
            lock = null;

            if (current != null) {
                current.unlock();
            }
        }
    }

    private static final class CountingFiles extends HostFiles {
        private int mutations;

        private CountingFiles(File root) {
            super(root);
        }

        @Override
        protected void mutated() {
            mutations++;
        }

        private void reset() {
            mutations = 0;
        }
    }

    private static final class FailAfterFiles extends HostFiles {
        private final int failure;
        private int mutations;

        private FailAfterFiles(File root, int failure) {
            super(root);
            this.failure = failure;
        }

        @Override
        protected void mutated() throws IOException {
            mutations++;

            if (mutations == failure) {
                throw new IOException("Injected failure");
            }
        }
    }

    private static final class FailOnDeleteFiles extends HostFiles {
        private FailOnDeleteFiles(File root) {
            super(root);
        }

        @Override
        public void delete(String name) throws IOException {
            if (PluginReputationStore.TRANSACTION_FILE.equals(name)) {
                throw new IOException("Injected transaction cleanup failure");
            }

            super.delete(name);
        }
    }
}
