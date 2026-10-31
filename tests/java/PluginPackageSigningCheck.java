import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;
import org.telegram.messenger.plugins.signing.PluginSignatureVerification;
import org.telegram.messenger.plugins.signing.PluginSignatureVerifier;
import org.telegram.messenger.plugins.signing.PluginSignedPayload;
import org.telegram.messenger.plugins.signing.PluginKeyRotationProof;
import org.telegram.messenger.plugins.signing.PluginSignerIdentity;
import org.telegram.messenger.plugins.signing.PluginSignerTransition;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PluginPackageSigningCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        canonicalPayload();
        envelopeFormat();
        verification();
        malformedInput();
        rotationProof();
        signerTransition();
    }

    private static void canonicalPayload() {
        PluginSignedPayload first = payload(entries(
                file("plugin.py", 7, 'b'),
                file("manifest.json", 11, 'a')));
        PluginSignedPayload second = payload(entries(
                file("manifest.json", 11, 'a'),
                file("plugin.py", 7, 'b')));

        byte[] testKey = new byte[] {1, 2, 3};

        require(Arrays.equals(first.encode(1, testKey), second.encode(1, testKey)));
        require(hex(sha256(first.encode(1, testKey))).equals(
                "4fb11f506543076bb1def3bda1791b08d58139596be954773ac4f443596ab9e3"));
        require(first.entries.get(0).path.equals("manifest.json"));
        require(first.entries.get(1).path.equals("plugin.py"));
        expectUnsupported(() -> first.entries.clear());

        require(!Arrays.equals(
                first.encode(1, testKey),
                payload(entries(
                        file("manifest.json", 11, 'a'),
                        file("plugin.py", 8, 'b'))).encode(1, testKey)));
        require(!Arrays.equals(
                first.encode(1, testKey),
                payload(entries(
                        file("manifest.json", 11, 'a'),
                        file("plugin.py", 7, 'c'))).encode(1, testKey)));
        require(!Arrays.equals(first.encode(1, testKey), first.encode(2, testKey)));
        require(!Arrays.equals(first.encode(1, testKey), first.encode(1, new byte[] {1, 2, 4})));

        expectInvalid(() -> payload(Collections.singletonList(file("plugin.py", 7, 'b'))));
        expectInvalid(() -> payload(entries(
                file("manifest.json", 11, 'a'),
                file("manifest.json", 11, 'a'))));
        expectInvalid(() -> file(PluginSignedPayload.SIGNATURE_ENTRY, 1, 'a'));
        expectInvalid(() -> file(PluginSignedPayload.SIGNATURE_ENTRY + "/nested", 1, 'a'));
        expectInvalid(() -> file("../plugin.py", 1, 'a'));
        expectInvalid(() -> new PluginSignedPayload.Entry("plugin.py", false, 1, digest('A')));
        expectInvalid(() -> new PluginSignedPayload.Entry("manifest.json", true, 0, digest('a')));

        PluginSignedPayload withDirectory = payload(entries(
                file("manifest.json", 11, 'a'),
                directory("lib"),
                file("lib/plugin.py", 7, 'b')));
        require(!Arrays.equals(first.encode(1, testKey), withDirectory.encode(1, testKey)));
    }

    private static void envelopeFormat() throws Exception {
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                new byte[] {1, 2, 3},
                new byte[] {4, 5, 6});
        PluginSignatureEnvelope decoded = PluginSignatureEnvelope.decode(envelope.encode());

        require(decoded.equals(envelope));
        require(hex(envelope.encode()).equals(
                "4647534500000001000000010000000301020300000003040506"));
        byte[] key = decoded.publicKey();
        key[0] = 9;
        require(decoded.publicKey()[0] == 1);
        byte[] signature = decoded.signature();
        signature[0] = 9;
        require(decoded.signature()[0] == 4);

        byte[] future = envelope.encode();
        future[7] = 2;
        expectIo(() -> PluginSignatureEnvelope.decode(future));

        byte[] trailing = Arrays.copyOf(envelope.encode(), envelope.encode().length + 1);
        expectIo(() -> PluginSignatureEnvelope.decode(trailing));
        expectIo(() -> PluginSignatureEnvelope.decode(null));
    }

    private static void verification() throws Exception {
        KeyPair firstKey = keyPair();
        KeyPair secondKey = keyPair();
        PluginSignedPayload payload = payload(entries(
                file("manifest.json", 11, 'a'),
                file("plugin.py", 7, 'b')));
        byte[] signature = sign(firstKey, payload.encode(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                firstKey.getPublic().getEncoded()));
        PluginSignatureEnvelope envelope = envelope(firstKey, signature);
        PluginSignatureVerification verified = PluginSignatureVerifier.verify(payload, envelope);

        require(verified.status == PluginSignatureVerification.Status.VERIFIED);
        require(verified.isVerified());
        require(verified.keyId.matches("p256:[a-f0-9]{64}"));
        require(verified.payloadSha256.matches("[a-f0-9]{64}"));
        require(PluginSignatureVerifier.verify(payload, null).status
                == PluginSignatureVerification.Status.MISSING_SIGNATURE);
        require(PluginSignatureVerifier.verify(
                payload,
                new PluginSignatureEnvelope(2, firstKey.getPublic().getEncoded(), signature)).status
                == PluginSignatureVerification.Status.UNSUPPORTED_ALGORITHM);

        PluginSignedPayload changed = payload(entries(
                file("manifest.json", 12, 'a'),
                file("plugin.py", 7, 'b')));
        require(PluginSignatureVerifier.verify(changed, envelope).status
                == PluginSignatureVerification.Status.INVALID_SIGNATURE);
        require(PluginSignatureVerifier.verify(changed, envelope).keyId == null);
        require(PluginSignatureVerifier.verify(
                payload,
                envelope(secondKey, signature)).status
                == PluginSignatureVerification.Status.INVALID_SIGNATURE);

        PluginSignatureVerification repeated = PluginSignatureVerifier.verify(payload, envelope);
        require(repeated.keyId.equals(verified.keyId));
    }

    private static void malformedInput() throws Exception {
        KeyPair key = keyPair();
        PluginSignedPayload payload = payload(entries(file("manifest.json", 11, 'a')));
        byte[] signature = sign(key, payload.encode(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded()));
        byte[] malformedKey = key.getPublic().getEncoded();
        malformedKey[0] = 0;

        require(PluginSignatureVerifier.verify(
                payload,
                new PluginSignatureEnvelope(
                        PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                        malformedKey,
                        signature)).status
                == PluginSignatureVerification.Status.MALFORMED_PUBLIC_KEY);

        byte[] trailingSignature = Arrays.copyOf(signature, signature.length + 1);
        require(PluginSignatureVerifier.verify(
                payload,
                envelope(key, trailingSignature)).status
                == PluginSignatureVerification.Status.MALFORMED_SIGNATURE);

        byte[] highS = highS(signature);
        require(PluginSignatureVerifier.verify(payload, envelope(key, highS)).status
                == PluginSignatureVerification.Status.MALFORMED_SIGNATURE);
        require(PluginSignatureVerifier.verifyEncoded(payload, new byte[] {1, 2, 3}).status
                == PluginSignatureVerification.Status.MALFORMED_ENVELOPE);
        expectInvalid(() -> PluginSignatureVerifier.verify(null, null));
    }

    private static void rotationProof() throws Exception {
        require(hex(sha256(PluginKeyRotationProof.message(
                "example.plugin",
                "p256:" + digest('a'),
                "p256:" + digest('b'),
                3,
                4))).equals(
                "8c3580ec4d6292a7b98a85f31b408688998c21311e693543d62d5efab8311c89"));

        KeyPair oldKey = keyPair();
        KeyPair newKey = keyPair();
        PluginSignatureVerification oldIdentity = verified(oldKey);
        PluginSignatureVerification newIdentity = verified(newKey);
        byte[] message = PluginKeyRotationProof.message(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                3,
                4);
        PluginSignatureEnvelope oldEnvelope = envelope(oldKey, sign(oldKey, message));
        PluginSignatureEnvelope newEnvelope = envelope(newKey, sign(newKey, message));
        PluginKeyRotationProof proof = new PluginKeyRotationProof(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                3,
                4,
                oldEnvelope,
                newEnvelope);

        require(proof.verify().status == PluginKeyRotationProof.Status.VERIFIED);
        require(Arrays.equals(message, proof.message()));

        PluginKeyRotationProof wrongOld = new PluginKeyRotationProof(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                3,
                4,
                newEnvelope,
                newEnvelope);
        require(wrongOld.verify().status == PluginKeyRotationProof.Status.KEY_ID_MISMATCH);

        PluginKeyRotationProof wrongNewSignature = new PluginKeyRotationProof(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                3,
                4,
                oldEnvelope,
                envelope(newKey, sign(oldKey, message)));
        require(wrongNewSignature.verify().status
                == PluginKeyRotationProof.Status.NEW_SIGNATURE_INVALID);

        byte[] wrongMessage = PluginKeyRotationProof.message(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                4,
                5);
        PluginKeyRotationProof staleSignatures = new PluginKeyRotationProof(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                4,
                5,
                oldEnvelope,
                newEnvelope);
        require(!Arrays.equals(message, wrongMessage));
        require(staleSignatures.verify().status
                == PluginKeyRotationProof.Status.OLD_SIGNATURE_INVALID);

        expectInvalid(() -> PluginKeyRotationProof.message(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                3,
                5));
        expectInvalid(() -> PluginKeyRotationProof.message(
                "example.plugin",
                oldIdentity.keyId,
                oldIdentity.keyId,
                3,
                4));
        expectInvalid(() -> PluginKeyRotationProof.message(
                "example.plugin",
                oldIdentity.keyId,
                newIdentity.keyId,
                Long.MAX_VALUE,
                Long.MIN_VALUE));
    }

    private static void signerTransition() throws Exception {
        KeyPair oldKey = keyPair();
        KeyPair newKey = keyPair();
        KeyPair thirdKey = keyPair();
        PluginSignatureVerification unsigned = PluginSignatureVerifier.verify(basePayload(), null);
        PluginSignatureVerification oldVerification = verified(oldKey);
        PluginSignatureVerification newVerification = verified(newKey);
        PluginSignatureVerification thirdVerification = verified(thirdKey);
        String firstDigest = digest('1');
        String secondDigest = digest('2');

        PluginSignerTransition.Result firstUnsigned = PluginSignerTransition.evaluate(
                "example.plugin", firstDigest, null, unsigned, null);
        require(firstUnsigned.decision == PluginSignerTransition.Decision.CONFIRM);
        require(firstUnsigned.nextIdentity.kind == PluginSignerIdentity.Kind.UNSIGNED);
        require(PluginSignerTransition.evaluate(
                "example.plugin",
                firstDigest,
                firstUnsigned.nextIdentity,
                unsigned,
                null).decision
                == PluginSignerTransition.Decision.ACCEPT);
        PluginSignerTransition.Result changedUnsigned = PluginSignerTransition.evaluate(
                "example.plugin",
                secondDigest,
                firstUnsigned.nextIdentity,
                unsigned,
                null);
        require(changedUnsigned.decision == PluginSignerTransition.Decision.CONFIRM);
        require(changedUnsigned.reason == PluginSignerTransition.Reason.UNSIGNED_PACKAGE_CHANGED);
        require(changedUnsigned.nextIdentity.packageSha256.equals(secondDigest));

        PluginSignerTransition.Result signedUpgrade = PluginSignerTransition.evaluate(
                "example.plugin",
                secondDigest,
                firstUnsigned.nextIdentity,
                oldVerification,
                null);
        require(signedUpgrade.decision == PluginSignerTransition.Decision.CONFIRM);
        require(signedUpgrade.reason == PluginSignerTransition.Reason.UPGRADE_UNSIGNED_TO_SIGNED);

        PluginSignerTransition.Result firstSigned = PluginSignerTransition.evaluate(
                "example.plugin", firstDigest, null, oldVerification, null);
        PluginSignerIdentity pinned = firstSigned.nextIdentity;
        require(firstSigned.decision == PluginSignerTransition.Decision.CONFIRM);
        require(pinned.kind == PluginSignerIdentity.Kind.SIGNED);
        require(PluginSignerTransition.evaluate(
                "example.plugin", firstDigest, pinned, oldVerification, null).decision
                == PluginSignerTransition.Decision.ACCEPT);
        PluginSignerTransition.Result downgrade = PluginSignerTransition.evaluate(
                "example.plugin", secondDigest, pinned, unsigned, null);
        require(downgrade.decision == PluginSignerTransition.Decision.BLOCK);
        require(downgrade.reason == PluginSignerTransition.Reason.UNSIGNED_DOWNGRADE);
        require(downgrade.nextIdentity.equals(pinned));
        PluginSignerTransition.Result keyChanged = PluginSignerTransition.evaluate(
                "example.plugin", secondDigest, pinned, newVerification, null);
        require(keyChanged.decision == PluginSignerTransition.Decision.BLOCK);
        require(keyChanged.reason == PluginSignerTransition.Reason.KEY_CHANGED);
        require(keyChanged.nextIdentity.equals(pinned));

        byte[] message = PluginKeyRotationProof.message(
                "example.plugin",
                oldVerification.keyId,
                newVerification.keyId,
                0,
                1);
        PluginKeyRotationProof proof = new PluginKeyRotationProof(
                "example.plugin",
                oldVerification.keyId,
                newVerification.keyId,
                0,
                1,
                envelope(oldKey, sign(oldKey, message)),
                envelope(newKey, sign(newKey, message)));
        PluginSignerTransition.Result rotated = PluginSignerTransition.evaluate(
                "example.plugin", secondDigest, pinned, newVerification, proof);

        require(rotated.decision == PluginSignerTransition.Decision.ACCEPT);
        require(rotated.reason == PluginSignerTransition.Reason.VERIFIED_ROTATION);
        require(rotated.nextIdentity.generation == 1);
        require(rotated.nextIdentity.keyId.equals(newVerification.keyId));

        byte[] secondMessage = PluginKeyRotationProof.message(
                "example.plugin",
                newVerification.keyId,
                thirdVerification.keyId,
                1,
                2);
        PluginKeyRotationProof secondProof = new PluginKeyRotationProof(
                "example.plugin",
                newVerification.keyId,
                thirdVerification.keyId,
                1,
                2,
                envelope(newKey, sign(newKey, secondMessage)),
                envelope(thirdKey, sign(thirdKey, secondMessage)));
        PluginSignerTransition.Result secondRotation = PluginSignerTransition.evaluate(
                "example.plugin",
                digest('3'),
                rotated.nextIdentity,
                thirdVerification,
                secondProof);
        require(secondRotation.decision == PluginSignerTransition.Decision.ACCEPT);
        require(secondRotation.nextIdentity.generation == 2);
        assertBlocked(PluginSignerTransition.evaluate(
                "example.plugin",
                firstDigest,
                rotated.nextIdentity,
                oldVerification,
                proof), PluginSignerTransition.Reason.INVALID_ROTATION, rotated.nextIdentity);
        assertBlocked(PluginSignerTransition.evaluate(
                "different.plugin",
                firstDigest,
                pinned,
                oldVerification,
                null), PluginSignerTransition.Reason.PLUGIN_ID_MISMATCH, pinned);

        PluginSignatureVerification otherPlugin = verified(oldKey, "different.plugin");
        assertBlocked(PluginSignerTransition.evaluate(
                "example.plugin",
                firstDigest,
                pinned,
                otherPlugin,
                null), PluginSignerTransition.Reason.PLUGIN_ID_MISMATCH, pinned);

        PluginSignatureEnvelope invalidEnvelope = envelope(newKey, sign(newKey, basePayload().encode(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                newKey.getPublic().getEncoded())));
        byte[] invalidSignature = invalidEnvelope.signature();
        invalidSignature[invalidSignature.length - 1] ^= 1;
        PluginSignatureVerification invalid = PluginSignatureVerifier.verify(
                basePayload(),
                new PluginSignatureEnvelope(
                        PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                        newKey.getPublic().getEncoded(),
                        invalidSignature));
        assertBlocked(PluginSignerTransition.evaluate(
                "example.plugin",
                firstDigest,
                pinned,
                invalid,
                null), PluginSignerTransition.Reason.INVALID_SIGNATURE, pinned);
    }

    private static PluginSignatureVerification verified(KeyPair key) throws Exception {
        return verified(key, "example.plugin");
    }

    private static PluginSignatureVerification verified(KeyPair key, String pluginId)
            throws Exception {
        PluginSignedPayload payload = new PluginSignedPayload(
                pluginId,
                "1.2.3",
                5,
                entries(file("manifest.json", 11, 'a')));
        byte[] message = payload.encode(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded());
        return PluginSignatureVerifier.verify(payload, envelope(key, sign(key, message)));
    }

    private static PluginSignedPayload basePayload() {
        return payload(entries(file("manifest.json", 11, 'a')));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] sign(KeyPair key, byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(payload);
        return lowS(signer.sign());
    }

    private static PluginSignatureEnvelope envelope(KeyPair key, byte[] signature) {
        return new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded(),
                signature);
    }

    private static byte[] lowS(byte[] signature) {
        BigInteger[] values = decodeSignature(signature);
        BigInteger s = values[1];

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        return encodeSignature(values[0], s);
    }

    private static byte[] highS(byte[] signature) {
        BigInteger[] values = decodeSignature(signature);
        return encodeSignature(values[0], P256_ORDER.subtract(values[1]));
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

        return encoded;
    }

    private static PluginSignedPayload payload(List<PluginSignedPayload.Entry> entries) {
        return new PluginSignedPayload("example.plugin", "1.2.3", 5, entries);
    }

    private static List<PluginSignedPayload.Entry> entries(PluginSignedPayload.Entry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static PluginSignedPayload.Entry file(String path, long size, char digest) {
        return new PluginSignedPayload.Entry(path, false, size, digest(digest));
    }

    private static PluginSignedPayload.Entry directory(String path) {
        return new PluginSignedPayload.Entry(
                path,
                true,
                0,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    private static String digest(char value) {
        char[] result = new char[64];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
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

    private static void expectInvalid(CheckedRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void assertBlocked(
            PluginSignerTransition.Result result,
            PluginSignerTransition.Reason reason,
            PluginSignerIdentity identity) {
        require(result.decision == PluginSignerTransition.Decision.BLOCK);
        require(result.reason == reason);
        require(result.nextIdentity.equals(identity));
    }

    private static void expectUnsupported(CheckedRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void expectIo(CheckedRunnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IOException");
        } catch (java.io.IOException expected) {
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
