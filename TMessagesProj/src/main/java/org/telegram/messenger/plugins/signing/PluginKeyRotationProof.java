package org.telegram.messenger.plugins.signing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PluginKeyRotationProof {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x4647524b;
    private static final String DOMAIN = "fluxgram.plugin.key-rotation";

    public enum Status {
        VERIFIED,
        OLD_SIGNATURE_INVALID,
        NEW_SIGNATURE_INVALID,
        KEY_ID_MISMATCH
    }

    public static final class Verification {
        public final Status status;
        public final PluginSignatureVerification oldSignature;
        public final PluginSignatureVerification newSignature;

        Verification(
                Status status,
                PluginSignatureVerification oldSignature,
                PluginSignatureVerification newSignature) {
            this.status = status;
            this.oldSignature = oldSignature;
            this.newSignature = newSignature;
        }

        public boolean isVerified() {
            return status == Status.VERIFIED;
        }
    }

    public final String pluginId;
    public final String fromKeyId;
    public final String toKeyId;
    public final long fromGeneration;
    public final long toGeneration;
    public final PluginSignatureEnvelope oldSignature;
    public final PluginSignatureEnvelope newSignature;

    public PluginKeyRotationProof(
            String pluginId,
            String fromKeyId,
            String toKeyId,
            long fromGeneration,
            long toGeneration,
            PluginSignatureEnvelope oldSignature,
            PluginSignatureEnvelope newSignature) {
        validateTransition(pluginId, fromKeyId, toKeyId, fromGeneration, toGeneration);

        if (oldSignature == null || newSignature == null) {
            throw new IllegalArgumentException("Missing key rotation signature");
        }

        this.pluginId = pluginId;
        this.fromKeyId = fromKeyId;
        this.toKeyId = toKeyId;
        this.fromGeneration = fromGeneration;
        this.toGeneration = toGeneration;
        this.oldSignature = oldSignature;
        this.newSignature = newSignature;
    }

    public byte[] message() {
        return message(pluginId, fromKeyId, toKeyId, fromGeneration, toGeneration);
    }

    public Verification verify() {
        byte[] message = message();
        PluginSignatureVerification oldVerification =
                PluginSignatureVerifier.verifyCanonicalMessage(message, oldSignature);
        PluginSignatureVerification newVerification =
                PluginSignatureVerifier.verifyCanonicalMessage(message, newSignature);

        if (!oldVerification.isVerified()) {
            return new Verification(
                    Status.OLD_SIGNATURE_INVALID,
                    oldVerification,
                    newVerification);
        }
        if (!newVerification.isVerified()) {
            return new Verification(
                    Status.NEW_SIGNATURE_INVALID,
                    oldVerification,
                    newVerification);
        }
        if (!fromKeyId.equals(oldVerification.keyId)
                || !toKeyId.equals(newVerification.keyId)) {
            return new Verification(
                    Status.KEY_ID_MISMATCH,
                    oldVerification,
                    newVerification);
        }

        return new Verification(Status.VERIFIED, oldVerification, newVerification);
    }

    public static byte[] message(
            String pluginId,
            String fromKeyId,
            String toKeyId,
            long fromGeneration,
            long toGeneration) {
        validateTransition(pluginId, fromKeyId, toKeyId, fromGeneration, toGeneration);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, DOMAIN);
            writeString(output, pluginId);
            writeString(output, fromKeyId);
            writeString(output, toKeyId);
            output.writeLong(fromGeneration);
            output.writeLong(toGeneration);
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode key rotation message", error);
        }

        return bytes.toByteArray();
    }

    private static void validateTransition(
            String pluginId,
            String fromKeyId,
            String toKeyId,
            long fromGeneration,
            long toGeneration) {
        if (!PluginSignerIdentity.isPluginId(pluginId)
                || !PluginSignerIdentity.isKeyId(fromKeyId)
                || !PluginSignerIdentity.isKeyId(toKeyId)
                || fromKeyId.equals(toKeyId)
                || fromGeneration < 0
                || fromGeneration == Long.MAX_VALUE
                || toGeneration != fromGeneration + 1) {
            throw new IllegalArgumentException("Invalid key rotation transition");
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
