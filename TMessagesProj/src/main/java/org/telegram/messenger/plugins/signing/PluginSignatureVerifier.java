package org.telegram.messenger.plugins.signing;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public final class PluginSignatureVerifier {
    private static final byte[] P256_SPKI_PREFIX = hexBytes(
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004");
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);
    private static final BigInteger P256_HALF_ORDER = P256_ORDER.shiftRight(1);
    private static final BigInteger P256_FIELD = new BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            16);
    private static final BigInteger P256_B = new BigInteger(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
            16);

    private PluginSignatureVerifier() {
    }

    public static PluginSignatureVerification verify(
            PluginSignedPayload payload,
            PluginSignatureEnvelope envelope) {
        if (payload == null) {
            throw new IllegalArgumentException("Missing signed payload");
        }

        if (envelope == null) {
            return result(
                    PluginSignatureVerification.Status.MISSING_SIGNATURE,
                    payload.pluginId,
                    null,
                    null);
        }

        byte[] publicKeyBytes = envelope.publicKey();
        byte[] encodedPayload = payload.encode(envelope.algorithm, publicKeyBytes);

        PluginSignatureVerification verification =
                verifyCanonicalMessage(encodedPayload, envelope);
        return result(
                verification.status,
                payload.pluginId,
                verification.keyId,
                verification.payloadSha256);
    }

    static PluginSignatureVerification verifyCanonicalMessage(
            byte[] encodedPayload,
            PluginSignatureEnvelope envelope) {
        if (encodedPayload == null) {
            throw new IllegalArgumentException("Missing canonical signed message");
        }
        if (envelope == null) {
            return result(
                    PluginSignatureVerification.Status.MISSING_SIGNATURE,
                    null,
                    null,
                    null);
        }

        byte[] publicKeyBytes = envelope.publicKey();
        String payloadSha256;

        try {
            payloadSha256 = hex(MessageDigest.getInstance("SHA-256").digest(encodedPayload));
        } catch (NoSuchAlgorithmException | ProviderException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    null);
        }

        if (envelope.algorithm != PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256) {
            return result(
                    PluginSignatureVerification.Status.UNSUPPORTED_ALGORITHM,
                    null,
                    payloadSha256);
        }

        PublicKey publicKey;

        try {
            publicKey = decodePublicKey(publicKeyBytes);
        } catch (NoSuchAlgorithmException | ProviderException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    payloadSha256);
        } catch (InvalidKeySpecException error) {
            return result(
                    PluginSignatureVerification.Status.MALFORMED_PUBLIC_KEY,
                    null,
                    payloadSha256);
        }

        String keyId;

        try {
            keyId = "p256:" + hex(MessageDigest.getInstance("SHA-256").digest(publicKeyBytes));
        } catch (NoSuchAlgorithmException | ProviderException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    payloadSha256);
        }

        byte[] signatureBytes = envelope.signature();

        if (!isCanonicalSignature(signatureBytes)) {
            return result(
                    PluginSignatureVerification.Status.MALFORMED_SIGNATURE,
                    null,
                    payloadSha256);
        }

        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update(encodedPayload);
            boolean verified = signature.verify(signatureBytes);

            return result(
                    verified
                            ? PluginSignatureVerification.Status.VERIFIED
                            : PluginSignatureVerification.Status.INVALID_SIGNATURE,
                    verified ? keyId : null,
                    payloadSha256);
        } catch (NoSuchAlgorithmException | ProviderException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    payloadSha256);
        } catch (InvalidKeyException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    payloadSha256);
        } catch (SignatureException error) {
            return result(
                    PluginSignatureVerification.Status.PROVIDER_UNAVAILABLE,
                    null,
                    payloadSha256);
        }
    }

    public static PluginSignatureVerification verifyEncoded(
            PluginSignedPayload payload,
            byte[] encodedEnvelope) {
        try {
            return verify(payload, PluginSignatureEnvelope.decode(encodedEnvelope));
        } catch (java.io.IOException error) {
            return result(
                    PluginSignatureVerification.Status.MALFORMED_ENVELOPE,
                    payload == null ? null : payload.pluginId,
                    null,
                    null);
        }
    }

    private static PublicKey decodePublicKey(byte[] encoded)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (encoded.length != P256_SPKI_PREFIX.length + 64
                || !startsWith(encoded, P256_SPKI_PREFIX)) {
            throw new InvalidKeySpecException("Public key is not canonical P-256 SPKI");
        }

        BigInteger x = new BigInteger(
                1,
                Arrays.copyOfRange(encoded, P256_SPKI_PREFIX.length, P256_SPKI_PREFIX.length + 32));
        BigInteger y = new BigInteger(
                1,
                Arrays.copyOfRange(encoded, P256_SPKI_PREFIX.length + 32, encoded.length));

        if (x.compareTo(P256_FIELD) >= 0
                || y.compareTo(P256_FIELD) >= 0
                || !y.multiply(y).mod(P256_FIELD).equals(
                        x.multiply(x).multiply(x)
                                .subtract(x.multiply(BigInteger.valueOf(3)))
                                .add(P256_B)
                                .mod(P256_FIELD))) {
            throw new InvalidKeySpecException("Public key point is not on P-256");
        }

        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));

        if (!Arrays.equals(key.getEncoded(), encoded)) {
            throw new InvalidKeySpecException("Public key encoding is not canonical");
        }

        return key;
    }

    private static boolean isCanonicalSignature(byte[] encoded) {
        if (encoded.length < 8 || encoded.length > 72 || encoded[0] != 0x30) {
            return false;
        }

        int sequenceLength = encoded[1] & 255;

        if (sequenceLength != encoded.length - 2) {
            return false;
        }

        int offset = 2;
        IntegerValue r = readInteger(encoded, offset);

        if (r == null) {
            return false;
        }

        offset = r.end;
        IntegerValue s = readInteger(encoded, offset);

        return s != null
                && s.end == encoded.length
                && r.value.signum() > 0
                && r.value.compareTo(P256_ORDER) < 0
                && s.value.signum() > 0
                && s.value.compareTo(P256_HALF_ORDER) <= 0;
    }

    private static IntegerValue readInteger(byte[] encoded, int offset) {
        if (offset + 3 > encoded.length || encoded[offset] != 0x02) {
            return null;
        }

        int length = encoded[offset + 1] & 255;
        int start = offset + 2;
        int end = start + length;

        if (length == 0 || length > 33 || end > encoded.length) {
            return null;
        }
        if ((encoded[start] & 128) != 0) {
            return null;
        }
        if (length > 1 && encoded[start] == 0 && (encoded[start + 1] & 128) == 0) {
            return null;
        }

        return new IntegerValue(new BigInteger(1, Arrays.copyOfRange(encoded, start, end)), end);
    }

    private static PluginSignatureVerification result(
            PluginSignatureVerification.Status status,
            String keyId,
            String payloadSha256) {
        return result(status, null, keyId, payloadSha256);
    }

    private static PluginSignatureVerification result(
            PluginSignatureVerification.Status status,
            String pluginId,
            String keyId,
            String payloadSha256) {
        return new PluginSignatureVerification(status, pluginId, keyId, payloadSha256);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }

        return true;
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

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }

        return result;
    }

    private static final class IntegerValue {
        final BigInteger value;
        final int end;

        IntegerValue(BigInteger value, int end) {
            this.value = value;
            this.end = end;
        }
    }
}
