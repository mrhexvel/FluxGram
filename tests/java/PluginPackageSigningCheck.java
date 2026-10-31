import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;
import org.telegram.messenger.plugins.signing.PluginSignatureVerification;
import org.telegram.messenger.plugins.signing.PluginSignatureVerifier;
import org.telegram.messenger.plugins.signing.PluginSignedPayload;

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
