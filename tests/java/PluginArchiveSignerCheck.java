import org.telegram.messenger.plugins.packages.PluginManifest;
import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;
import org.telegram.messenger.plugins.signing.PluginSignedPayload;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PluginArchiveSignerCheck {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);

    public static void main(String[] args) throws Exception {
        List<PluginSignedPayload.Entry> entries = new ArrayList<>();
        byte[] manifestBytes = null;

        try (ZipFile zip = new ZipFile(args[0])) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();

            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();

                if (entry.getName().equals(PluginSignedPayload.SIGNATURE_ENTRY)) {
                    throw new IllegalArgumentException("Archive is already signed");
                }

                byte[] content;

                try (InputStream input = zip.getInputStream(entry)) {
                    content = read(input);
                }

                entries.add(new PluginSignedPayload.Entry(
                        entry.isDirectory()
                                ? entry.getName().substring(0, entry.getName().length() - 1)
                                : entry.getName(),
                        entry.isDirectory(),
                        content.length,
                        hex(MessageDigest.getInstance("SHA-256").digest(content))));

                if (entry.getName().equals("manifest.json") && !entry.isDirectory()) {
                    manifestBytes = content;
                }
            }
        }

        if (manifestBytes == null) {
            throw new IllegalArgumentException("Missing manifest");
        }

        PluginManifest manifest = PluginManifest.parse(new String(manifestBytes, "UTF-8"));
        PluginSignedPayload payload = new PluginSignedPayload(
                manifest.id,
                manifest.version,
                manifest.schemaVersion,
                entries);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        byte[] publicKey = key.getPublic().getEncoded();
        byte[] message = payload.encode(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                publicKey);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        byte[] signature = lowS(signer.sign());
        PluginSignatureEnvelope envelope = new PluginSignatureEnvelope(
                PluginSignatureEnvelope.ALGORITHM_ECDSA_P256_SHA256,
                publicKey,
                signature);
        System.out.println(Base64.getEncoder().encodeToString(envelope.encode()));
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;

        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }

        return output.toByteArray();
    }

    private static byte[] lowS(byte[] signature) {
        int rLength = signature[3] & 255;
        int rStart = 4;
        int sOffset = rStart + rLength;
        int sLength = signature[sOffset + 1] & 255;
        int sStart = sOffset + 2;
        BigInteger r = new BigInteger(
                1,
                Arrays.copyOfRange(signature, rStart, rStart + rLength));
        BigInteger s = new BigInteger(
                1,
                Arrays.copyOfRange(signature, sStart, sStart + sLength));

        if (s.compareTo(P256_ORDER.shiftRight(1)) > 0) {
            s = P256_ORDER.subtract(s);
        }

        return encodeSignature(r, s);
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
