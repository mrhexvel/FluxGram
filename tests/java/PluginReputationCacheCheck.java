import org.telegram.messenger.plugins.reputation.PluginReputationCache;
import org.telegram.messenger.plugins.reputation.PluginReputationPolicy;
import org.telegram.messenger.plugins.reputation.PluginReputationResponse;
import org.telegram.messenger.plugins.reputation.PluginReputationVerifier;
import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

public final class PluginReputationCacheCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        roundTripAndDeterminism();
        malformedSnapshots();
        authorityBinding();
        monotonicAndAnchorPolicy();
        baselineRestore();
        evictionAndCapacity();
        immutability();
    }

    private static void roundTripAndDeterminism() throws Exception {
        KeyPair authority = keyPair();
        String authorityKeyId = keyId(authority);
        Signed first = signed(authority, clear(identity(2), null, 1, 100, 300));
        Signed second = signed(authority, clear(identity(1), keyId('a'), 1, 100, 300));

        PluginReputationCache left = accepted(PluginReputationCache.empty(), first, authorityKeyId, 150);
        left = accepted(left, second, authorityKeyId, 150);
        PluginReputationCache right = accepted(PluginReputationCache.empty(), second, authorityKeyId, 150);
        right = accepted(right, first, authorityKeyId, 150);

        byte[] encoded = left.encode();
        require(Arrays.equals(encoded, right.encode()));
        require(encoded.length < PluginReputationCache.MAX_ENCODED_BYTES);
        require(readInt(encoded, 0) == 0x46475243);
        require(readInt(encoded, 4) == PluginReputationCache.FORMAT_VERSION);
        require(readLong(encoded, 8) == 2);
        require(readInt(encoded, 16) == 2);
        int payloadLength = encoded.length - 32;
        require(MessageDigest.isEqual(
                Arrays.copyOfRange(encoded, payloadLength, encoded.length),
                MessageDigest.getInstance("SHA-256").digest(
                        Arrays.copyOf(encoded, payloadLength))));

        PluginReputationCache decoded = PluginReputationCache.decode(encoded, authorityKeyId, 150);
        require(decoded.generation() == 2);
        require(decoded.size() == 2);
        require(Arrays.equals(decoded.encode(), encoded));
        require(Arrays.equals(decoded.currentResponseBytes(first.response.archiveSha256, null), first.responseBytes));
        require(Arrays.equals(decoded.currentEnvelopeBytes(first.response.archiveSha256, null), first.envelopeBytes));

        PluginReputationCache.LookupResult exact = decoded.lookup(
                second.response.archiveSha256,
                second.response.developerKeyId,
                authorityKeyId,
                150);
        require(exact.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(exact.authenticated && exact.fresh && !exact.stale);
        require(exact.reasons.isEmpty());

        PluginReputationCache.LookupResult wrongIdentity = decoded.lookup(
                identity(3),
                second.response.developerKeyId,
                authorityKeyId,
                150);
        require(!wrongIdentity.authenticated);
        require(wrongIdentity.reasons.equals(Arrays.asList(PluginReputationPolicy.RECORD_UNAVAILABLE)));
    }

    private static void malformedSnapshots() throws Exception {
        KeyPair authority = keyPair();
        KeyPair other = keyPair();
        String authorityKeyId = keyId(authority);
        Signed record = signed(authority, clear(identity(1), null, 1, 100, 200));
        PluginReputationCache cache = accepted(
                PluginReputationCache.empty(),
                record,
                authorityKeyId,
                150);
        byte[] encoded = cache.encode();

        byte[] changed = encoded.clone();
        changed[20] ^= 1;
        expectIo(() -> PluginReputationCache.decode(changed, authorityKeyId, 150));
        expectIo(() -> PluginReputationCache.decode(
                Arrays.copyOf(encoded, encoded.length - 1),
                authorityKeyId,
                150));
        expectIo(() -> PluginReputationCache.decode(encoded, keyId(other), 150));
        expectIo(() -> PluginReputationCache.decode(new byte[PluginReputationCache.MAX_ENCODED_BYTES + 1], authorityKeyId, 150));

        byte[] badVersion = encoded.clone();
        badVersion[7] = 2;
        refreshChecksum(badVersion);
        expectIo(() -> PluginReputationCache.decode(badVersion, authorityKeyId, 150));

        byte[] badCount = encoded.clone();
        writeInt(badCount, 16, PluginReputationCache.MAX_ENTRIES + 1);
        refreshChecksum(badCount);
        expectIo(() -> PluginReputationCache.decode(badCount, authorityKeyId, 150));

        byte[] oversizedResponse = encoded.clone();
        writeInt(oversizedResponse, 20, 129);
        refreshChecksum(oversizedResponse);
        expectIo(() -> PluginReputationCache.decode(oversizedResponse, authorityKeyId, 150));

        byte[] corruptedSignature = encoded.clone();
        int responseLength = readInt(corruptedSignature, 20);
        int envelopeLengthOffset = 24 + responseLength;
        int envelopeLength = readInt(corruptedSignature, envelopeLengthOffset);
        int envelopeOffset = envelopeLengthOffset + 4;
        corruptedSignature[envelopeOffset + envelopeLength - 1] ^= 1;
        refreshChecksum(corruptedSignature);
        expectIo(() -> PluginReputationCache.decode(
                corruptedSignature,
                authorityKeyId,
                150));

        byte[] trailing = new byte[encoded.length + 1];
        int payloadLength = encoded.length - 32;
        System.arraycopy(encoded, 0, trailing, 0, payloadLength);
        trailing[payloadLength] = 1;
        System.arraycopy(encoded, payloadLength, trailing, payloadLength + 1, 32);
        refreshChecksum(trailing);
        expectIo(() -> PluginReputationCache.decode(trailing, authorityKeyId, 150));

        Signed second = signed(authority, clear(identity(2), null, 1, 100, 200));
        PluginReputationCache two = accepted(cache, second, authorityKeyId, 150);
        byte[][] records = recordBytes(two.encode());
        expectIo(() -> PluginReputationCache.decode(
                snapshot(2, records[1], records[0]),
                authorityKeyId,
                150));
        expectIo(() -> PluginReputationCache.decode(
                snapshot(2, records[0], records[0]),
                authorityKeyId,
                150));
        byte[] pair = Arrays.copyOf(records[0], records[0].length - 4);
        expectIo(() -> PluginReputationCache.decode(
                snapshot(1, anchoredRecord(pair)),
                authorityKeyId,
                150));

        Signed clearCurrent = signed(authority, clear(identity(3), null, 2, 100, 300));
        Signed wrongIdentityAnchor = signed(authority, blocked(identity(4), null, 1, 100, 200));
        expectIo(() -> PluginReputationCache.decode(
                snapshot(1, anchoredRecord(
                        encodedPair(clearCurrent),
                        encodedPair(wrongIdentityAnchor))),
                authorityKeyId,
                150));

        Signed equalRevisionAnchor = signed(authority, blocked(identity(3), null, 2, 100, 200));
        expectIo(() -> PluginReputationCache.decode(
                snapshot(1, anchoredRecord(
                        encodedPair(clearCurrent),
                        encodedPair(equalRevisionAnchor))),
                authorityKeyId,
                150));

        expectInvalid(() -> PluginReputationCache.decode(encoded, "bad", 150));
        expectInvalid(() -> cache.lookup(identity(1), null, "bad", 150));
    }

    private static void authorityBinding() throws Exception {
        KeyPair authorityA = keyPair();
        KeyPair authorityB = keyPair();
        String authorityAKeyId = keyId(authorityA);
        String authorityBKeyId = keyId(authorityB);
        Signed initial = signed(authorityA, blocked(identity(1), null, 1, 100, 300));
        PluginReputationCache cache = accepted(
                PluginReputationCache.empty(),
                initial,
                authorityAKeyId,
                150);
        byte[] before = cache.encode();

        Signed sameIdentity = signed(authorityB, clear(identity(1), null, 2, 150, 350));
        PluginReputationCache.UpdateResult sameResult = cache.update(
                sameIdentity.responseBytes,
                sameIdentity.envelopeBytes,
                authorityBKeyId,
                150);
        require(sameResult.status == PluginReputationCache.UpdateStatus.REJECTED_AUTHORITY);
        require(sameResult.cache == cache);

        Signed differentIdentity = signed(authorityB, clear(identity(2), null, 1, 150, 350));
        PluginReputationCache.UpdateResult differentResult = cache.update(
                differentIdentity.responseBytes,
                differentIdentity.envelopeBytes,
                authorityBKeyId,
                150);
        require(differentResult.status == PluginReputationCache.UpdateStatus.REJECTED_AUTHORITY);
        require(differentResult.cache == cache);
        require(Arrays.equals(before, cache.encode()));

        expectInvalid(() -> cache.lookup(identity(1), null, authorityBKeyId, 150));
        require(cache.lookup(identity(1), null, authorityAKeyId, 150).decision
                == PluginReputationPolicy.Decision.BLOCK);

        PluginReputationCache decoded = PluginReputationCache.decode(before, authorityAKeyId, 150);
        PluginReputationCache.UpdateResult decodedWrongAuthority = decoded.update(
                differentIdentity.responseBytes,
                differentIdentity.envelopeBytes,
                authorityBKeyId,
                150);
        require(decodedWrongAuthority.status
                == PluginReputationCache.UpdateStatus.REJECTED_AUTHORITY);
    }

    private static void monotonicAndAnchorPolicy() throws Exception {
        KeyPair authority = keyPair();
        String authorityKeyId = keyId(authority);
        String archive = identity(10);
        String developer = keyId('b');
        Signed blocked = signed(authority, blocked(archive, developer, 5, 100, 200));
        PluginReputationCache cache = accepted(
                PluginReputationCache.empty(),
                blocked,
                authorityKeyId,
                150);

        PluginReputationCache.LookupResult freshBlock = cache.lookup(archive, developer, authorityKeyId, 150);
        require(freshBlock.decision == PluginReputationPolicy.Decision.BLOCK);
        require(freshBlock.fresh && !freshBlock.stale && !freshBlock.usedBlockingAnchor);
        require(freshBlock.reasons.equals(Arrays.asList(
                PluginReputationPolicy.BLOCK_ARCHIVE,
                PluginReputationPolicy.BLOCK_KEY)));

        PluginReputationCache.LookupResult staleBlock = cache.lookup(archive, developer, authorityKeyId, 250);
        require(staleBlock.decision == PluginReputationPolicy.Decision.BLOCK);
        require(!staleBlock.fresh && staleBlock.stale);

        Signed clear = signed(authority, clear(archive, developer, 6, 200, 300));
        PluginReputationCache cleared = accepted(cache, clear, authorityKeyId, 200);
        require(Arrays.equals(cleared.blockingAnchorResponseBytes(archive, developer), blocked.responseBytes));
        require(cleared.lookup(archive, developer, authorityKeyId, 250).decision
                == PluginReputationPolicy.Decision.NO_ASSERTION);

        PluginReputationCache.LookupResult rollback = cleared.lookup(archive, developer, authorityKeyId, 150);
        require(rollback.decision == PluginReputationPolicy.Decision.BLOCK);
        require(rollback.usedBlockingAnchor);

        byte[] anchorResponse = cleared.blockingAnchorResponseBytes(archive, developer);
        byte[] anchorEnvelope = cleared.blockingAnchorEnvelopeBytes(archive, developer);
        byte[] clearedSnapshot = cleared.encode();
        anchorResponse[0] ^= 1;
        anchorEnvelope[0] ^= 1;
        require(Arrays.equals(clearedSnapshot, cleared.encode()));
        require(Arrays.equals(
                cleared.blockingAnchorResponseBytes(archive, developer),
                blocked.responseBytes));
        require(Arrays.equals(
                cleared.blockingAnchorEnvelopeBytes(archive, developer),
                blocked.envelopeBytes));

        PluginReputationCache.UpdateResult replay = cleared.update(
                blocked.responseBytes,
                blocked.envelopeBytes,
                authorityKeyId,
                250);
        require(replay.status == PluginReputationCache.UpdateStatus.REJECTED_OLD);
        require(replay.cache == cleared);

        PluginReputationCache.UpdateResult unchanged = cleared.update(
                clear.responseBytes,
                clear.envelopeBytes,
                authorityKeyId,
                250);
        require(unchanged.status == PluginReputationCache.UpdateStatus.UNCHANGED);
        require(unchanged.cache == cleared);

        Signed conflict = signed(authority, blocked(archive, developer, 6, 200, 300));
        PluginReputationCache.UpdateResult conflicting = cleared.update(
                conflict.responseBytes,
                conflict.envelopeBytes,
                authorityKeyId,
                250);
        require(conflicting.status == PluginReputationCache.UpdateStatus.REJECTED_CONFLICT);
        require(conflicting.cache == cleared);

        Signed sameContentDifferentEnvelope = signed(authority, clear(archive, developer, 6, 200, 300));
        PluginReputationCache.UpdateResult exactConflict = cleared.update(
                sameContentDifferentEnvelope.responseBytes,
                sameContentDifferentEnvelope.envelopeBytes,
                authorityKeyId,
                250);
        require(exactConflict.status == PluginReputationCache.UpdateStatus.REJECTED_CONFLICT);
        require(exactConflict.cache == cleared);

        Signed future = signed(authority, clear(archive, developer, 7, 400, 500));
        PluginReputationCache.UpdateResult futureResult = cleared.update(
                future.responseBytes,
                future.envelopeBytes,
                authorityKeyId,
                350);
        require(futureResult.status == PluginReputationCache.UpdateStatus.REJECTED_FUTURE);
        require(futureResult.cache == cleared);

        Signed expired = signed(authority, clear(archive, developer, 7, 250, 350));
        PluginReputationCache expiredCache = accepted(cleared, expired, authorityKeyId, 350);
        PluginReputationCache.LookupResult staleClear = expiredCache.lookup(
                archive,
                developer,
                authorityKeyId,
                350);
        require(staleClear.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(staleClear.authenticated && staleClear.stale && !staleClear.fresh);
        require(staleClear.reasons.equals(Arrays.asList(PluginReputationPolicy.RECORD_STALE)));
        require(staleClear.usedBlockingAnchor == false);

        Signed blockedAgain = signed(authority, blocked(archive, developer, 8, 300, 400));
        PluginReputationCache newBlock = accepted(expiredCache, blockedAgain, authorityKeyId, 350);
        require(newBlock.blockingAnchorResponseBytes(archive, developer) == null);
        require(newBlock.lookup(archive, developer, authorityKeyId, 350).decision
                == PluginReputationPolicy.Decision.BLOCK);

        byte[] rollbackSnapshot = cleared.encode();
        PluginReputationCache decodedAfterRollback = PluginReputationCache.decode(
                rollbackSnapshot,
                authorityKeyId,
                150);
        require(decodedAfterRollback.lookup(archive, developer, authorityKeyId, 150).usedBlockingAnchor);

        Signed futureClear = signed(authority, clear(identity(20), null, 1, 400, 500));
        PluginReputationCache futureCache = accepted(
                PluginReputationCache.empty(),
                futureClear,
                authorityKeyId,
                400);
        PluginReputationCache.LookupResult futureLookup = futureCache.lookup(
                identity(20),
                null,
                authorityKeyId,
                350);
        require(futureLookup.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(futureLookup.authenticated && futureLookup.stale && !futureLookup.fresh);
        require(!futureLookup.usedBlockingAnchor);
        require(futureLookup.reasons.equals(Arrays.asList(
                PluginReputationPolicy.RECORD_STALE)));

        PluginReputationCache maxGeneration = PluginReputationCache.decode(
                withGeneration(futureCache.encode(), Long.MAX_VALUE),
                authorityKeyId,
                400);
        Signed newer = signed(authority, clear(identity(20), null, 2, 400, 600));
        PluginReputationCache.UpdateResult maxGenerationUpdate = maxGeneration.update(
                newer.responseBytes,
                newer.envelopeBytes,
                authorityKeyId,
                450);
        require(maxGenerationUpdate.status
                == PluginReputationCache.UpdateStatus.REJECTED_CAPACITY);
        require(maxGenerationUpdate.cache == maxGeneration);
    }

    private static void baselineRestore() throws Exception {
        KeyPair authority = keyPair();
        String authorityKeyId = keyId(authority);
        Signed clear = signed(authority, clear(identity(1), null, 1, 100, 300));
        PluginReputationCache clearCache = accepted(
                PluginReputationCache.empty(),
                clear,
                authorityKeyId,
                150);
        byte[] clearSnapshot = clearCache.encode();
        Signed block = signed(authority, blocked(identity(1), null, 2, 150, 350));
        PluginReputationCache blockedCache = accepted(
                clearCache,
                block,
                authorityKeyId,
                200);

        expectIo(() -> PluginReputationCache.restore(
                clearSnapshot,
                authorityKeyId,
                200,
                blockedCache));
        expectIo(() -> PluginReputationCache.restore(
                withGeneration(clearSnapshot, blockedCache.generation() + 1),
                authorityKeyId,
                200,
                blockedCache));

        Signed unanchoredClear = signed(authority, clear(identity(1), null, 3, 200, 400));
        PluginReputationCache unanchoredClearCache = accepted(
                PluginReputationCache.empty(),
                unanchoredClear,
                authorityKeyId,
                250);
        expectIo(() -> PluginReputationCache.restore(
                withGeneration(
                        unanchoredClearCache.encode(),
                        blockedCache.generation() + 1),
                authorityKeyId,
                250,
                blockedCache));

        PluginReputationCache anchoredClearCache = accepted(
                blockedCache,
                unanchoredClear,
                authorityKeyId,
                250);
        PluginReputationCache restoredAnchoredClear = PluginReputationCache.restore(
                anchoredClearCache.encode(),
                authorityKeyId,
                250,
                blockedCache);
        require(Arrays.equals(restoredAnchoredClear.encode(), anchoredClearCache.encode()));
        Signed laterUnanchoredClear = signed(
                authority,
                clear(identity(1), null, 4, 250, 450));
        PluginReputationCache laterUnanchoredCache = accepted(
                PluginReputationCache.empty(),
                laterUnanchoredClear,
                authorityKeyId,
                300);
        expectIo(() -> PluginReputationCache.restore(
                withGeneration(
                        laterUnanchoredCache.encode(),
                        anchoredClearCache.generation() + 1),
                authorityKeyId,
                300,
                anchoredClearCache));

        Signed baselineClear = signed(authority, clear(identity(4), null, 10, 100, 500));
        PluginReputationCache baselineClearCache = accepted(
                PluginReputationCache.empty(),
                baselineClear,
                authorityKeyId,
                200);
        Signed oldBlock = signed(authority, blocked(identity(4), null, 1, 100, 500));
        Signed nextClear = signed(authority, clear(identity(4), null, 11, 200, 600));
        PluginReputationCache oldAnchorCache = accepted(
                accepted(
                        PluginReputationCache.empty(),
                        oldBlock,
                        authorityKeyId,
                        200),
                nextClear,
                authorityKeyId,
                250);
        expectIo(() -> PluginReputationCache.restore(
                oldAnchorCache.encode(),
                authorityKeyId,
                250,
                baselineClearCache));

        Signed conflictingBlock = signed(authority, blocked(identity(4), null, 10, 150, 550));
        PluginReputationCache conflictingAnchorCache = accepted(
                accepted(
                        PluginReputationCache.empty(),
                        conflictingBlock,
                        authorityKeyId,
                        200),
                nextClear,
                authorityKeyId,
                250);
        expectIo(() -> PluginReputationCache.restore(
                conflictingAnchorCache.encode(),
                authorityKeyId,
                250,
                baselineClearCache));

        Signed newerBlock = signed(authority, blocked(identity(4), null, 11, 200, 600));
        Signed newestClear = signed(authority, clear(identity(4), null, 12, 250, 650));
        PluginReputationCache newerAnchorCache = accepted(
                accepted(
                        PluginReputationCache.empty(),
                        newerBlock,
                        authorityKeyId,
                        250),
                newestClear,
                authorityKeyId,
                300);
        PluginReputationCache restoredNewerAnchor = PluginReputationCache.restore(
                newerAnchorCache.encode(),
                authorityKeyId,
                300,
                baselineClearCache);
        require(Arrays.equals(restoredNewerAnchor.encode(), newerAnchorCache.encode()));

        Signed other = signed(authority, clear(identity(2), null, 1, 100, 300));
        PluginReputationCache otherCache = accepted(
                PluginReputationCache.empty(),
                other,
                authorityKeyId,
                150);
        require(otherCache.generation() == clearCache.generation());
        expectIo(() -> PluginReputationCache.restore(
                otherCache.encode(),
                authorityKeyId,
                150,
                clearCache));

        PluginReputationCache same = PluginReputationCache.restore(
                clearSnapshot,
                authorityKeyId,
                150,
                clearCache);
        require(Arrays.equals(same.encode(), clearSnapshot));

        Signed future = signed(authority, clear(identity(3), null, 1, 400, 500));
        PluginReputationCache futureInitial = accepted(
                PluginReputationCache.empty(),
                future,
                authorityKeyId,
                400);
        expectIo(() -> PluginReputationCache.restore(
                withGeneration(
                        futureInitial.encode(),
                        clearCache.generation() + 1),
                authorityKeyId,
                350,
                clearCache));

        expectInvalid(() -> PluginReputationCache.restore(
                clearSnapshot,
                keyId(keyPair()),
                150,
                clearCache));
        expectInvalid(() -> PluginReputationCache.restore(
                clearSnapshot,
                authorityKeyId,
                150,
                null));
        expectIo(() -> PluginReputationCache.restore(
                null,
                authorityKeyId,
                150,
                clearCache));
    }

    private static void evictionAndCapacity() throws Exception {
        KeyPair authority = keyPair();
        String authorityKeyId = keyId(authority);
        PluginReputationCache cache = PluginReputationCache.empty();

        for (int i = 0; i < PluginReputationCache.MAX_ENTRIES; i++) {
            Signed record = signed(authority, clear(identity(i + 1), null, 1, 100 + i, 1_000 + i));
            cache = accepted(cache, record, authorityKeyId, 500);
        }

        require(cache.size() == PluginReputationCache.MAX_ENTRIES);
        Signed replacement = signed(authority, clear(identity(500), null, 1, 200, 2_000));
        PluginReputationCache evicted = accepted(cache, replacement, authorityKeyId, 500);
        require(evicted.size() == PluginReputationCache.MAX_ENTRIES);
        require(evicted.currentResponseBytes(identity(1), null) == null);
        require(evicted.currentResponseBytes(identity(2), null) != null);
        require(evicted.currentResponseBytes(identity(500), null) != null);
        PluginReputationCache restoredAfterEviction = PluginReputationCache.restore(
                evicted.encode(),
                authorityKeyId,
                500,
                cache);
        require(Arrays.equals(restoredAfterEviction.encode(), evicted.encode()));

        PluginReputationCache protectedCache = PluginReputationCache.empty();

        for (int i = 0; i < PluginReputationCache.MAX_ENTRIES; i++) {
            Signed record = signed(authority, blocked(identity(1_000 + i), null, 1, 100, 1_000));
            protectedCache = accepted(protectedCache, record, authorityKeyId, 500);
        }

        Signed protectedClear = signed(authority, clear(identity(1_000), null, 2, 400, 1_200));
        protectedCache = accepted(protectedCache, protectedClear, authorityKeyId, 500);
        require(protectedCache.blockingAnchorResponseBytes(identity(1_000), null) != null);

        byte[] before = protectedCache.encode();
        Signed overflow = signed(authority, clear(identity(2_000), null, 1, 100, 1_000));
        PluginReputationCache.UpdateResult rejected = protectedCache.update(
                overflow.responseBytes,
                overflow.envelopeBytes,
                authorityKeyId,
                500);
        require(rejected.status == PluginReputationCache.UpdateStatus.REJECTED_CAPACITY);
        require(rejected.cache == protectedCache);
        require(Arrays.equals(before, protectedCache.encode()));
    }

    private static void immutability() throws Exception {
        KeyPair authority = keyPair();
        String authorityKeyId = keyId(authority);
        Signed signed = signed(authority, clear(identity(1), null, 1, 100, 200));
        byte[] responseInput = signed.responseBytes.clone();
        byte[] envelopeInput = signed.envelopeBytes.clone();
        PluginReputationCache cache = accepted(
                PluginReputationCache.empty(),
                new Signed(signed.response, responseInput, envelopeInput),
                authorityKeyId,
                150);
        byte[] snapshot = cache.encode();

        responseInput[0] ^= 1;
        envelopeInput[0] ^= 1;
        byte[] responseOutput = cache.currentResponseBytes(signed.response.archiveSha256, null);
        byte[] envelopeOutput = cache.currentEnvelopeBytes(signed.response.archiveSha256, null);
        responseOutput[0] ^= 1;
        envelopeOutput[0] ^= 1;

        require(Arrays.equals(snapshot, cache.encode()));
        require(Arrays.equals(cache.currentResponseBytes(signed.response.archiveSha256, null), signed.responseBytes));
        require(Arrays.equals(cache.currentEnvelopeBytes(signed.response.archiveSha256, null), signed.envelopeBytes));
        expectUnsupported(() -> cache.lookup(
                signed.response.archiveSha256,
                null,
                authorityKeyId,
                150).reasons.clear());
    }

    private static PluginReputationCache accepted(
            PluginReputationCache cache,
            Signed signed,
            String authorityKeyId,
            long nowMillis) {
        PluginReputationCache.UpdateResult result = cache.update(
                signed.responseBytes,
                signed.envelopeBytes,
                authorityKeyId,
                nowMillis);
        require(result.status == PluginReputationCache.UpdateStatus.ACCEPTED);
        return result.cache;
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
        return new Signed(response, responseBytes, envelope.encode());
    }

    private static PluginReputationResponse clear(
            String archive,
            String developer,
            long revision,
            long issuedAt,
            long expiresAt) {
        return new PluginReputationResponse(
                archive,
                developer,
                revision,
                issuedAt,
                expiresAt,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                developer == null
                        ? PluginReputationResponse.KeyStatus.NOT_APPLICABLE
                        : PluginReputationResponse.KeyStatus.CLEAR);
    }

    private static PluginReputationResponse blocked(
            String archive,
            String developer,
            long revision,
            long issuedAt,
            long expiresAt) {
        return new PluginReputationResponse(
                archive,
                developer,
                revision,
                issuedAt,
                expiresAt,
                PluginReputationResponse.ArchiveStatus.MALICIOUS,
                developer == null
                        ? PluginReputationResponse.KeyStatus.NOT_APPLICABLE
                        : PluginReputationResponse.KeyStatus.REVOKED);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String keyId(KeyPair key) throws Exception {
        return "p256:" + hex(MessageDigest.getInstance("SHA-256").digest(key.getPublic().getEncoded()));
    }

    private static String keyId(char value) {
        char[] digest = new char[64];
        Arrays.fill(digest, value);
        return "p256:" + new String(digest);
    }

    private static String identity(int value) {
        return String.format("%064x", value);
    }

    private static byte[] sign(KeyPair key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return lowS(signer.sign());
    }

    private static byte[] lowS(byte[] signature) {
        BigInteger[] values = decodeSignature(signature);
        BigInteger s = values[1];

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        return encodeSignature(values[0], s);
    }

    private static BigInteger[] decodeSignature(byte[] signature) {
        int rLength = signature[3] & 255;
        int rStart = 4;
        int sOffset = rStart + rLength;
        int sLength = signature[sOffset + 1] & 255;
        int sStart = sOffset + 2;
        return new BigInteger[] {
                new BigInteger(1, Arrays.copyOfRange(signature, rStart, rStart + rLength)),
                new BigInteger(1, Arrays.copyOfRange(signature, sStart, sStart + sLength))
        };
    }

    private static byte[] encodeSignature(BigInteger r, BigInteger s) {
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

    private static void refreshChecksum(byte[] encoded) throws Exception {
        int payloadLength = encoded.length - 32;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                Arrays.copyOf(encoded, payloadLength));
        System.arraycopy(digest, 0, encoded, payloadLength, digest.length);
    }

    private static byte[][] recordBytes(byte[] encoded) {
        int offset = 20;
        byte[][] records = new byte[2][];

        for (int i = 0; i < records.length; i++) {
            int start = offset;
            int responseLength = readInt(encoded, offset);
            offset += 4 + responseLength;
            int envelopeLength = readInt(encoded, offset);
            offset += 4 + envelopeLength;
            require(readInt(encoded, offset) == 0);
            offset += 4;
            records[i] = Arrays.copyOfRange(encoded, start, offset);
        }

        require(offset == encoded.length - 32);
        return records;
    }

    private static byte[] snapshot(long generation, byte[]... records) throws Exception {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(0x46475243);
            output.writeInt(PluginReputationCache.FORMAT_VERSION);
            output.writeLong(generation);
            output.writeInt(records.length);

            for (byte[] record : records) {
                output.write(record);
            }
        }

        byte[] payload = payloadBytes.toByteArray();
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(payload);
        byte[] result = Arrays.copyOf(payload, payload.length + checksum.length);
        System.arraycopy(checksum, 0, result, payload.length, checksum.length);
        return result;
    }

    private static byte[] anchoredRecord(byte[] pair) throws Exception {
        return anchoredRecord(pair, pair);
    }

    private static byte[] anchoredRecord(byte[] current, byte[] anchor) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(current);
            output.writeInt(1);
            output.write(anchor);
        }

        return bytes.toByteArray();
    }

    private static byte[] encodedPair(Signed signed) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(signed.responseBytes.length);
            output.write(signed.responseBytes);
            output.writeInt(signed.envelopeBytes.length);
            output.write(signed.envelopeBytes);
        }

        return bytes.toByteArray();
    }

    private static byte[] withGeneration(byte[] encoded, long generation) throws Exception {
        byte[] result = encoded.clone();

        for (int i = 0; i < 8; i++) {
            result[8 + i] = (byte) (generation >>> (56 - i * 8));
        }

        refreshChecksum(result);
        return result;
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

    private static void expectInvalid(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void expectUnsupported(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
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

    private static final class Signed {
        private final PluginReputationResponse response;
        private final byte[] responseBytes;
        private final byte[] envelopeBytes;

        private Signed(
                PluginReputationResponse response,
                byte[] responseBytes,
                byte[] envelopeBytes) {
            this.response = response;
            this.responseBytes = responseBytes;
            this.envelopeBytes = envelopeBytes;
        }
    }
}
