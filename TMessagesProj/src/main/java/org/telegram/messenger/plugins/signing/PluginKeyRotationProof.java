package org.telegram.messenger.plugins.signing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class PluginKeyRotationProof {
    public static final int FORMAT_VERSION = 1;
    public static final String ENTRY = ".fluxgram-key-rotation";
    public static final int MAX_ENCODED_BYTES = 2048;

    private static final int MESSAGE_MAGIC = 0x4647524b;
    private static final int PROOF_MAGIC = 0x46475250;
    private static final String DOMAIN = "fluxgram.plugin.key-rotation";
    private static final int MAX_ENVELOPE_BYTES = 512;

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

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(PROOF_MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, pluginId);
            writeString(output, fromKeyId);
            writeString(output, toKeyId);
            output.writeLong(fromGeneration);
            output.writeLong(toGeneration);
            writeBytes(output, oldSignature.encode());
            writeBytes(output, newSignature.encode());
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Key rotation proof exceeds size limit");
        }

        return encoded;
    }

    public static PluginKeyRotationProof decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid key rotation proof size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != PROOF_MAGIC) {
                throw new IOException("Invalid key rotation proof header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported key rotation proof version");
            }

            String pluginId = readString(input, 100);
            String fromKeyId = readString(input, 69);
            String toKeyId = readString(input, 69);
            long fromGeneration = input.readLong();
            long toGeneration = input.readLong();
            PluginSignatureEnvelope oldSignature = PluginSignatureEnvelope.decode(
                    readBytes(input, MAX_ENVELOPE_BYTES));
            PluginSignatureEnvelope newSignature = PluginSignatureEnvelope.decode(
                    readBytes(input, MAX_ENVELOPE_BYTES));

            if (input.read() != -1) {
                throw new IOException("Trailing key rotation proof data");
            }

            return new PluginKeyRotationProof(
                    pluginId,
                    fromKeyId,
                    toKeyId,
                    fromGeneration,
                    toGeneration,
                    oldSignature,
                    newSignature);
        } catch (EOFException error) {
            throw new IOException("Truncated key rotation proof", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid key rotation proof content", error);
        }
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
            output.writeInt(MESSAGE_MAGIC);
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

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid key rotation proof string length");
        }

        byte[] encoded = new byte[length];
        input.readFully(encoded);

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid key rotation proof UTF-8", error);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid key rotation proof field length");
        }

        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }
}
