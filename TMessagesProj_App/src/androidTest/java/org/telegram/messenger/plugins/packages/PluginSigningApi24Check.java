package org.telegram.messenger.plugins.packages;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import org.telegram.messenger.plugins.signing.PluginKeyRotationProof;
import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;
import org.telegram.messenger.plugins.signing.PluginSignatureVerification;
import org.telegram.messenger.plugins.signing.PluginSignatureVerifier;
import org.telegram.messenger.plugins.signing.PluginSignedPayload;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PluginSigningApi24Check {
    private static final BigInteger P256_ORDER = new BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16);
    private static final byte[] MANIFEST = decode(
            "ewogICJzY2hlbWFfdmVyc2lvbiI6IDYsCiAgImlkIjogInRlc3QuYXBpMjQuc2lnbmluZyIsCiAgIm5hbWUiOiAiQVBJIDI0IHNpZ25pbmcgdGVzdCIsCiAgInZlcnNpb24iOiAiMS4wLjAiLAogICJkZXNjcmlwdGlvbiI6ICJQcm92aWRlciB2ZXJpZmljYXRpb24iLAogICJhdXRob3IiOiAiRmx1eEdyYW0iLAogICJzZGtfdmVyc2lvbiI6IDIsCiAgIm1pbl9zZGtfbWlub3IiOiAxMiwKICAiZW50cnlwb2ludCI6ICJtYWluOlBsdWdpbkVudHJ5IiwKICAiYWNjZXNzIjogImZ1bGwiLAogICJkZWNsYXJlZF9jYXBhYmlsaXRpZXMiOiBbXQp9Cg==");
    private static final byte[] PYTHON = decode(
            "Y2xhc3MgUGx1Z2luRW50cnk6CiAgICBwYXNzCg==");
    private static final byte[] ENVELOPE = decode(
            "RkdTRQAAAAEAAAABAAAAWzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABFRSo8ZQvnlMgKOyo8coB11Q8L9SBIaGE/r4b2Wtu7wH8IcQvlJOJArsVFo7qX+PWmROnWcFhJr5W/QgJTum4UMAAABHMEUCIQDzzokO3C3Mrzomq2m193+zCY9yzf1TIwO2TWHI7HQoAAIgDHF5WCmObQmIoNzFMs+2ESt48T4KrZY80FOIehZhYWI=");
    private static final byte[] ROTATION_PROOF = decode(
            "RkdSUAAAAAEAAAAOZXhhbXBsZS5wbHVnaW4AAABFcDI1Njo3YjdkYWFiM2M3ZTcxZDY4MjYwMGFlYzA5MzViZTdjNjY3MmEyYmNmZTkzMzQwNTY0YzAzNTgwMDk0YTUzNjMzAAAARXAyNTY6NzJiNDcyZWUwZjQ4Njc4NzVhMTUyMmQ1Nzg4ZDk0ZDAyM2E1MDE4NDJjMjYxZjJjMjQ1ZWJiNjBlMzc0OTMwYgAAAAAAAAAAAAAAAAAAAAEAAAC1RkdTRQAAAAEAAAABAAAAWzBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABFlKKx3dZBzh66qRbMhihcygethYsrVLgSoXv/Co+nVEzIexx5ykjMPbHiQJgQrhFH02HNSkOFG/rkoRf/sp3hgAAABGMEQCIGmICbdK5/K0PVo7pMz1K+LZaoMW2xvUGFplLegJrmG1AiAFV418FTkvHnFQepi0G37ZcL2KiQWYFIVLIEBVznSf4QAAALZGR1NFAAAAAQAAAAEAAABbMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYnfB9cslQR9x6F5RzThTUo73+zTC2NPqbKw+vOPYNbYJNk5WKR1iFXhkhxtzvbrfd8pF9CIBVr11Ro2qA3XD5AAAAEcwRQIhALIPvO/Tc3G8BYDAE8rFbsgcIRqnqy5rurh9kMiVv8pGAiBC8ey0LOUhC0y14P2aTwqTnzFV1XBGeexNvtjdXVnJnA==");

    private PluginSigningApi24Check() {
    }

    public static String run(Context context) throws Exception {
        require(Build.VERSION.SDK_INT == 24, "Expected Android API 24");
        String keyProvider = KeyFactory.getInstance("EC").getProvider().getName();
        String signatureProvider = Signature.getInstance("SHA256withECDSA")
                .getProvider().getName();
        require(!keyProvider.isEmpty(), "Missing EC provider");
        require(!signatureProvider.isEmpty(),
                "Missing ECDSA provider");

        PluginSignedPayload payload = payload(MANIFEST, PYTHON);
        PluginSignatureEnvelope envelope = PluginSignatureEnvelope.decode(ENVELOPE);
        PluginSignatureVerification verified = PluginSignatureVerifier.verify(payload, envelope);

        require(verified.status == PluginSignatureVerification.Status.VERIFIED,
                "Fixed signature was not verified");
        require("p256:23b35b5cd468652f7f7e551af42fefeb25e9fbdab570b16528d2ae6e053c3fb7"
                        .equals(verified.keyId),
                "Unexpected fixed key ID");
        require("261b862c4c9ba2939ec939b0e0dba366bb8879622a9ee2d052d1aceecb9dcc85"
                        .equals(verified.payloadSha256),
                "Unexpected fixed payload digest");

        PluginSignatureEnvelope highS = new PluginSignatureEnvelope(
                envelope.algorithm,
                envelope.publicKey(),
                highS(envelope.signature()));
        require(PluginSignatureVerifier.verify(payload, highS).status
                        == PluginSignatureVerification.Status.MALFORMED_SIGNATURE,
                "High-S signature was accepted");

        byte[] trailing = Arrays.copyOf(envelope.signature(), envelope.signature().length + 1);
        PluginSignatureEnvelope malformed = new PluginSignatureEnvelope(
                envelope.algorithm,
                envelope.publicKey(),
                trailing);
        require(PluginSignatureVerifier.verify(payload, malformed).status
                        == PluginSignatureVerification.Status.MALFORMED_SIGNATURE,
                "Malformed DER signature was accepted");

        PluginKeyRotationProof proof = PluginKeyRotationProof.decode(ROTATION_PROOF);
        require(proof.verify().status == PluginKeyRotationProof.Status.VERIFIED,
                "Fixed rotation proof was not verified");

        File archive = File.createTempFile("plugin-api24-", ".fgplugin", context.getCacheDir());
        File changed = File.createTempFile("plugin-api24-changed-", ".fgplugin", context.getCacheDir());

        try {
            writeArchive(archive, PYTHON);
            PluginPackageValidator.Result inspected =
                    PluginPackageValidator.inspectInstalledArchive(archive, context.getCacheDir());
            require(inspected.signatureVerification.status
                            == PluginSignatureVerification.Status.VERIFIED,
                    "Signed archive was not verified");
            require(verified.keyId.equals(inspected.signatureVerification.keyId),
                    "Archive key ID changed");
            require("test.api24.signing".equals(inspected.manifest.id),
                    "Archive identity changed");

            writeArchive(changed, Arrays.copyOf(PYTHON, PYTHON.length + 1));
            boolean rejected = false;

            try {
                PluginPackageValidator.inspectInstalledArchive(changed, context.getCacheDir());
            } catch (IOException expected) {
                rejected = expected.getMessage().contains("signature verification failed");
            }

            require(rejected, "Changed signed archive was accepted");
        } finally {
            require(archive.delete(), "Signed archive fixture was not deleted");
            require(changed.delete(), "Changed archive fixture was not deleted");
        }

        return "EC=" + keyProvider + ", ECDSA=" + signatureProvider;
    }

    private static PluginSignedPayload payload(byte[] manifest, byte[] python) throws Exception {
        List<PluginSignedPayload.Entry> entries = new ArrayList<>();
        entries.add(entry("manifest.json", manifest));
        entries.add(entry("main.py", python));
        return new PluginSignedPayload("test.api24.signing", "1.0.0", 6, entries);
    }

    private static PluginSignedPayload.Entry entry(String path, byte[] value) throws Exception {
        return new PluginSignedPayload.Entry(
                path,
                false,
                value.length,
                hex(MessageDigest.getInstance("SHA-256").digest(value)));
    }

    private static void writeArchive(File file, byte[] python) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            writeEntry(output, "manifest.json", MANIFEST);
            writeEntry(output, "main.py", python);
            writeEntry(output, PluginSignedPayload.SIGNATURE_ENTRY, ENVELOPE);
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] value)
            throws Exception {
        output.putNextEntry(new ZipEntry(name));
        output.write(value);
        output.closeEntry();
    }

    private static byte[] highS(byte[] signature) {
        int rLength = signature[3] & 255;
        int rStart = 4;
        int sOffset = rStart + rLength;
        int sLength = signature[sOffset + 1] & 255;
        int sStart = sOffset + 2;
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, rStart, rStart + rLength));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, sStart, sStart + sLength));
        return encodeSignature(r, P256_ORDER.subtract(s));
    }

    private static byte[] encodeSignature(BigInteger r, BigInteger s) {
        byte[] encodedR = integerBytes(r);
        byte[] encodedS = integerBytes(s);
        byte[] result = new byte[6 + encodedR.length + encodedS.length];
        result[0] = 0x30;
        result[1] = (byte) (result.length - 2);
        result[2] = 0x02;
        result[3] = (byte) encodedR.length;
        System.arraycopy(encodedR, 0, result, 4, encodedR.length);
        int offset = 4 + encodedR.length;
        result[offset] = 0x02;
        result[offset + 1] = (byte) encodedS.length;
        System.arraycopy(encodedS, 0, result, offset + 2, encodedS.length);
        return result;
    }

    private static byte[] integerBytes(BigInteger value) {
        byte[] encoded = value.toByteArray();

        if (encoded.length > 1 && encoded[0] == 0 && (encoded[1] & 128) == 0) {
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        }

        return encoded;
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.DEFAULT);
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
