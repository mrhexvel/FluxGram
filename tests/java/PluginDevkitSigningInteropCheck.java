package org.telegram.messenger.plugins.signing;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

public final class PluginDevkitSigningInteropCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] arguments) throws Exception {
        if ("rotation-message".equals(arguments[0]) && arguments.length == 6) {
            byte[] message = PluginKeyRotationProof.message(
                    arguments[1],
                    arguments[2],
                    arguments[3],
                    Long.parseLong(arguments[4]),
                    Long.parseLong(arguments[5]));
            System.out.println(Base64.getEncoder().encodeToString(message));
            return;
        }

        if ("verify-rotation".equals(arguments[0]) && arguments.length == 2) {
            PluginKeyRotationProof proof = PluginKeyRotationProof.decode(
                    Base64.getDecoder().decode(arguments[1]));
            System.out.println(proof.verify().status);
            return;
        }

        if ("rotation-proof".equals(arguments[0]) && arguments.length == 3) {
            KeyPair oldKey = keyPair();
            KeyPair newKey = keyPair();
            String oldKeyId = keyId(oldKey);
            String newKeyId = keyId(newKey);
            long generation = Long.parseLong(arguments[2]);
            byte[] message = PluginKeyRotationProof.message(
                    arguments[1], oldKeyId, newKeyId, generation, generation + 1);
            PluginKeyRotationProof proof = new PluginKeyRotationProof(
                    arguments[1],
                    oldKeyId,
                    newKeyId,
                    generation,
                    generation + 1,
                    envelope(oldKey, message),
                    envelope(newKey, message));
            System.out.println(Base64.getEncoder().encodeToString(proof.encode()));
            return;
        }

        if (arguments.length < 2) {
            throw new IllegalArgumentException("Missing interop command");
        }
        byte[] payload = Base64.getDecoder().decode(arguments[1]);

        if ("verify".equals(arguments[0]) && arguments.length == 3) {
            PluginSignatureEnvelope envelope = PluginSignatureEnvelope.decode(
                    Base64.getDecoder().decode(arguments[2]));
            PluginSignatureVerification result =
                    PluginSignatureVerifier.verifyCanonicalMessage(payload, envelope);
            System.out.println(result.status + " " + result.keyId + " " + result.payloadSha256);
            return;
        }

        if ("sign".equals(arguments[0]) && arguments.length == 2) {
            System.out.println(Base64.getEncoder().encodeToString(
                    envelope(keyPair(), payload).encode()));
            return;
        }

        throw new IllegalArgumentException("Invalid interop command");
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

    private static PluginSignatureEnvelope envelope(KeyPair key, byte[] message) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        return new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                key.getPublic().getEncoded(),
                lowS(signer.sign()));
    }

    private static byte[] lowS(byte[] signature) {
        int rLength = signature[3] & 255;
        byte[] r = Arrays.copyOfRange(signature, 4, 4 + rLength);
        int sOffset = 4 + rLength;
        int sLength = signature[sOffset + 1] & 255;
        BigInteger s = new BigInteger(
                1,
                Arrays.copyOfRange(signature, sOffset + 2, sOffset + 2 + sLength));

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        byte[] encodedS = integer(s);
        byte[] body = new byte[2 + r.length + encodedS.length];
        body[0] = 0x02;
        body[1] = (byte) r.length;
        System.arraycopy(r, 0, body, 2, r.length);
        System.arraycopy(encodedS, 0, body, 2 + r.length, encodedS.length);
        byte[] result = new byte[2 + body.length];
        result[0] = 0x30;
        result[1] = (byte) body.length;
        System.arraycopy(body, 0, result, 2, body.length);
        return result;
    }

    private static byte[] integer(BigInteger value) {
        byte[] encoded = value.toByteArray();

        if (encoded[0] == 0 && encoded.length > 1 && (encoded[1] & 128) == 0) {
            encoded = Arrays.copyOfRange(encoded, 1, encoded.length);
        }

        byte[] result = new byte[2 + encoded.length];
        result[0] = 0x02;
        result[1] = (byte) encoded.length;
        System.arraycopy(encoded, 0, result, 2, encoded.length);
        return result;
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
}
