import org.telegram.messenger.plugins.reputation.PluginReputationPolicy;
import org.telegram.messenger.plugins.reputation.PluginReputationResponse;
import org.telegram.messenger.plugins.reputation.PluginReputationState;
import org.telegram.messenger.plugins.reputation.PluginReputationVerification;
import org.telegram.messenger.plugins.reputation.PluginReputationVerifier;
import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;

public final class PluginReputationCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        serialization();
        malformedResponses();
        verification();
        policy();
    }

    private static void serialization() throws Exception {
        PluginReputationResponse withoutKey = response(
                digest('a'),
                null,
                7,
                100,
                200,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE);
        byte[] encoded = withoutKey.encode();
        String expected = "4647525200000001"
                + digest('a')
                + "00000000"
                + "0000000000000007"
                + "0000000000000064"
                + "00000000000000c8"
                + "00000001"
                + "00000000";

        require(encoded.length == 76);
        require(hex(encoded).equals(expected));
        require(PluginReputationResponse.decode(encoded).equals(withoutKey));

        PluginReputationResponse withKey = response(
                digest('b'),
                keyId('c'),
                Long.MAX_VALUE,
                1_000,
                2_000,
                PluginReputationResponse.ArchiveStatus.MALICIOUS,
                PluginReputationResponse.KeyStatus.REVOKED);

        require(withKey.encode().length == 108);
        require(PluginReputationResponse.decode(withKey.encode()).equals(withKey));
        require(withKey.hashCode() == PluginReputationResponse.decode(withKey.encode()).hashCode());

        byte[] generatorKey = bytes(
                "3059301306072a8648ce3d020106082a8648ce3d03010703420004"
                        + "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
                        + "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
        byte[] canonical = PluginReputationVerifier.canonicalMessage(
                withoutKey,
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                generatorKey);
        String canonicalExpected = "464752530000000100000023"
                + hex("fluxgram.plugin.reputation.response".getBytes("UTF-8"))
                + "00000001"
                + "0000005b"
                + hex(generatorKey)
                + "0000004c"
                + expected;
        require(hex(canonical).equals(canonicalExpected));
    }

    private static void malformedResponses() throws Exception {
        byte[] valid = response(
                digest('a'),
                null,
                1,
                100,
                200,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE).encode();

        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        expectIo(() -> PluginReputationResponse.decode(badMagic));

        byte[] oldVersion = valid.clone();
        oldVersion[7] = 0;
        expectIo(() -> PluginReputationResponse.decode(oldVersion));

        byte[] futureVersion = valid.clone();
        futureVersion[7] = 2;
        expectIo(() -> PluginReputationResponse.decode(futureVersion));

        byte[] unknownKey = valid.clone();
        unknownKey[43] = 3;
        expectIo(() -> PluginReputationResponse.decode(unknownKey));

        byte[] negativeRevision = valid.clone();
        negativeRevision[44] = (byte) 0x80;
        expectIo(() -> PluginReputationResponse.decode(negativeRevision));

        byte[] unknownArchiveStatus = valid.clone();
        unknownArchiveStatus[71] = 3;
        expectIo(() -> PluginReputationResponse.decode(unknownArchiveStatus));

        byte[] unknownKeyStatus = valid.clone();
        unknownKeyStatus[75] = 3;
        expectIo(() -> PluginReputationResponse.decode(unknownKeyStatus));

        expectIo(() -> PluginReputationResponse.decode(Arrays.copyOf(valid, valid.length - 1)));
        expectIo(() -> PluginReputationResponse.decode(Arrays.copyOf(valid, valid.length + 1)));
        expectIo(() -> PluginReputationResponse.decode(new byte[129]));
        expectIo(() -> PluginReputationResponse.decode(null));

        expectInvalid(() -> response(
                digest('A'),
                null,
                0,
                1,
                2,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE));
        expectInvalid(() -> response(
                digest('a'),
                keyId('b'),
                0,
                1,
                2,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE));
        expectInvalid(() -> response(
                digest('a'),
                null,
                0,
                1,
                2,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.CLEAR));
        expectInvalid(() -> response(
                digest('a'),
                null,
                -1,
                1,
                2,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE));
        expectInvalid(() -> response(
                digest('a'),
                null,
                0,
                2,
                2,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE));
        expectInvalid(() -> response(
                digest('a'),
                null,
                0,
                1,
                1 + PluginReputationResponse.MAX_VALIDITY_MILLIS + 1,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.NOT_APPLICABLE));
    }

    private static void verification() throws Exception {
        KeyPair authority = keyPair();
        KeyPair attacker = keyPair();
        String authorityKeyId = keyId(authority);
        PluginReputationResponse response = response(
                digest('a'),
                keyId('b'),
                3,
                100,
                200,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.CLEAR);
        byte[] envelope = envelope(authority, response).encode();

        PluginReputationVerification verified = verify(
                response,
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                100);
        require(verified.status == PluginReputationVerification.Status.VERIFIED);
        require(verified.isAuthenticated());
        require(verified.isFresh());
        require(verified.response.equals(response));
        require(verified.authorityKeyId.equals(authorityKeyId));
        require(verified.payloadSha256.length() == 64);

        PluginReputationVerification future = verify(
                response,
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                99);
        require(future.status == PluginReputationVerification.Status.NOT_YET_VALID);
        require(future.isAuthenticated());
        require(!future.isFresh());

        PluginReputationVerification expired = verify(
                response,
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                200);
        require(expired.status == PluginReputationVerification.Status.EXPIRED);
        require(expired.isAuthenticated());

        PluginReputationVerification mismatch = verify(
                response,
                envelope,
                authorityKeyId,
                digest('c'),
                keyId('b'),
                150);
        require(mismatch.status == PluginReputationVerification.Status.IDENTITY_MISMATCH);
        require(mismatch.isAuthenticated());

        PluginReputationVerification keyMismatch = verify(
                response,
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('c'),
                150);
        require(keyMismatch.status == PluginReputationVerification.Status.IDENTITY_MISMATCH);

        PluginReputationVerification untrusted = verify(
                response,
                envelope(attacker, response).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150);
        require(untrusted.status == PluginReputationVerification.Status.UNTRUSTED_AUTHORITY);
        require(!untrusted.isAuthenticated());

        byte[] changed = response.encode();
        changed[71] = 2;
        PluginReputationVerification invalidSignature = PluginReputationVerifier.verifyEncoded(
                changed,
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150);
        require(invalidSignature.status == PluginReputationVerification.Status.INVALID_SIGNATURE);

        PluginSignatureEnvelope unsupportedEnvelope = envelope(
                authority,
                response,
                2);
        PluginReputationVerification unsupported = verify(
                response,
                unsupportedEnvelope.encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150);
        require(unsupported.status == PluginReputationVerification.Status.UNSUPPORTED_ALGORITHM);

        require(PluginReputationVerifier.verifyEncoded(
                new byte[] {1},
                envelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150).status == PluginReputationVerification.Status.MALFORMED_RESPONSE);
        require(PluginReputationVerifier.verifyEncoded(
                response.encode(),
                new byte[] {1},
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150).status == PluginReputationVerification.Status.MALFORMED_ENVELOPE);

        expectInvalid(() -> PluginReputationVerifier.verifyEncoded(
                response.encode(),
                envelope,
                "bad",
                digest('a'),
                keyId('b'),
                150));
    }

    private static void policy() throws Exception {
        KeyPair authority = keyPair();
        KeyPair attacker = keyPair();
        String authorityKeyId = keyId(authority);
        PluginReputationResponse blocked = response(
                digest('a'),
                keyId('b'),
                5,
                100,
                200,
                PluginReputationResponse.ArchiveStatus.MALICIOUS,
                PluginReputationResponse.KeyStatus.REVOKED);
        byte[] blockedEnvelope = envelope(authority, blocked).encode();

        PluginReputationVerification blockedVerification = verify(
                blocked,
                blockedEnvelope,
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150);
        PluginReputationState state = PluginReputationState.update(null, blockedVerification);
        PluginReputationVerification wrongIdentity = verify(
                blocked,
                blockedEnvelope,
                authorityKeyId,
                digest('c'),
                keyId('b'),
                150);
        PluginReputationVerification wrongAuthority = verify(
                blocked,
                envelope(attacker, blocked).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                150);
        require(PluginReputationState.update(state, wrongIdentity) == state);
        require(PluginReputationState.update(state, wrongAuthority) == state);

        PluginReputationPolicy.Result freshBlocked = PluginReputationPolicy.evaluate(state, 150);
        require(freshBlocked.decision == PluginReputationPolicy.Decision.BLOCK);
        require(freshBlocked.authenticated);
        require(freshBlocked.fresh);
        require(freshBlocked.reasons.equals(Arrays.asList(
                PluginReputationPolicy.BLOCK_ARCHIVE,
                PluginReputationPolicy.BLOCK_KEY)));
        expectUnsupported(() -> freshBlocked.reasons.clear());

        PluginReputationPolicy.Result staleBlocked = PluginReputationPolicy.evaluate(state, 200);
        require(staleBlocked.decision == PluginReputationPolicy.Decision.BLOCK);
        require(staleBlocked.authenticated);
        require(!staleBlocked.fresh);

        PluginReputationResponse cleared = response(
                digest('a'),
                keyId('b'),
                6,
                200,
                300,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.CLEAR);
        PluginReputationVerification clearVerification = verify(
                cleared,
                envelope(authority, cleared).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                200);
        state = PluginReputationState.update(state, clearVerification);
        PluginReputationPolicy.Result freshClear = PluginReputationPolicy.evaluate(state, 200);
        require(freshClear.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(freshClear.authenticated);
        require(freshClear.fresh);
        require(freshClear.reasons.isEmpty());

        PluginReputationPolicy.Result staleClear = PluginReputationPolicy.evaluate(state, 300);
        require(staleClear.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(staleClear.reasons.equals(Arrays.asList(PluginReputationPolicy.RECORD_STALE)));

        PluginReputationState replayed = PluginReputationState.update(state, blockedVerification);
        require(replayed == state);
        require(PluginReputationPolicy.evaluate(replayed, 250).decision
                == PluginReputationPolicy.Decision.NO_ASSERTION);

        PluginReputationResponse conflicting = response(
                digest('a'),
                keyId('b'),
                6,
                200,
                300,
                PluginReputationResponse.ArchiveStatus.MALICIOUS,
                PluginReputationResponse.KeyStatus.REVOKED);
        PluginReputationVerification conflictVerification = verify(
                conflicting,
                envelope(authority, conflicting).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                250);
        require(PluginReputationState.update(state, conflictVerification) == state);

        PluginReputationResponse newerBlocked = response(
                digest('a'),
                keyId('b'),
                7,
                220,
                320,
                PluginReputationResponse.ArchiveStatus.MALICIOUS,
                PluginReputationResponse.KeyStatus.REVOKED);
        PluginReputationVerification newerBlockedVerification = verify(
                newerBlocked,
                envelope(authority, newerBlocked).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                250);
        PluginReputationState blockedAgain = PluginReputationState.update(
                state,
                newerBlockedVerification);
        require(PluginReputationPolicy.evaluate(blockedAgain, 250).decision
                == PluginReputationPolicy.Decision.BLOCK);
        require(PluginReputationState.update(blockedAgain, clearVerification) == blockedAgain);

        PluginReputationResponse expiredClear = response(
                digest('a'),
                keyId('b'),
                8,
                200,
                300,
                PluginReputationResponse.ArchiveStatus.CLEAR,
                PluginReputationResponse.KeyStatus.CLEAR);
        PluginReputationVerification expiredClearVerification = verify(
                expiredClear,
                envelope(authority, expiredClear).encode(),
                authorityKeyId,
                digest('a'),
                keyId('b'),
                300);
        PluginReputationState clearedAfterExpiry = PluginReputationState.update(
                blockedAgain,
                expiredClearVerification);
        PluginReputationPolicy.Result staleAdministrativeClear =
                PluginReputationPolicy.evaluate(clearedAfterExpiry, 300);
        require(staleAdministrativeClear.decision
                == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(staleAdministrativeClear.authenticated);
        require(!staleAdministrativeClear.fresh);
        require(staleAdministrativeClear.reasons.equals(Arrays.asList(
                PluginReputationPolicy.RECORD_STALE)));

        PluginReputationPolicy.Result unavailable = PluginReputationPolicy.evaluate(null, 150);
        require(unavailable.decision == PluginReputationPolicy.Decision.NO_ASSERTION);
        require(!unavailable.authenticated);
        require(unavailable.reasons.equals(Arrays.asList(
                PluginReputationPolicy.RECORD_UNAVAILABLE)));

        PluginReputationState finalState = state;
        expectInvalid(() -> PluginReputationPolicy.evaluate(finalState, -1));
    }

    private static PluginReputationVerification verify(
            PluginReputationResponse response,
            byte[] envelope,
            String authorityKeyId,
            String archiveSha256,
            String developerKeyId,
            long nowMillis) throws Exception {
        return PluginReputationVerifier.verifyEncoded(
                response.encode(),
                envelope,
                authorityKeyId,
                archiveSha256,
                developerKeyId,
                nowMillis);
    }

    private static PluginReputationResponse response(
            String archiveSha256,
            String developerKeyId,
            long revision,
            long issuedAtMillis,
            long expiresAtMillis,
            PluginReputationResponse.ArchiveStatus archiveStatus,
            PluginReputationResponse.KeyStatus keyStatus) {
        return new PluginReputationResponse(
                archiveSha256,
                developerKeyId,
                revision,
                issuedAtMillis,
                expiresAtMillis,
                archiveStatus,
                keyStatus);
    }

    private static PluginSignatureEnvelope envelope(
            KeyPair key,
            PluginReputationResponse response) throws Exception {
        return envelope(
                key,
                response,
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256);
    }

    private static PluginSignatureEnvelope envelope(
            KeyPair key,
            PluginReputationResponse response,
            int algorithm) throws Exception {
        byte[] message = PluginReputationVerifier.canonicalMessage(
                response,
                algorithm,
                key.getPublic().getEncoded());
        return new PluginSignatureEnvelope(
                algorithm,
                key.getPublic().getEncoded(),
                sign(key, message));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String keyId(KeyPair key) throws Exception {
        return "p256:" + hex(MessageDigest.getInstance("SHA-256").digest(
                key.getPublic().getEncoded()));
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

    private static String digest(char value) {
        char[] result = new char[64];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static String keyId(char value) {
        return "p256:" + digest(value);
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

    private static byte[] bytes(String value) {
        byte[] result = new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }

        return result;
    }

    private static void expectInvalid(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void expectIo(ThrowingRunnable runnable) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
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
}
