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

public final class PluginSignerJournal {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x4647534a;
    private static final int MAX_ENCODED_BYTES = 4096;
    private static final int MAX_STATE_BYTES = 256;
    private static final int MAX_PROOF_BYTES = 2048;

    public final String pluginId;
    public final PluginSignerState before;
    public final PluginSignerState after;
    public final PluginSignerTransition.Reason reason;
    public final PluginKeyRotationProof rotationProof;

    public PluginSignerJournal(
            String pluginId,
            PluginSignerState before,
            PluginSignerState after,
            PluginSignerTransition.Reason reason,
            PluginKeyRotationProof rotationProof) {
        if (!PluginSignerIdentity.isPluginId(pluginId)
                || after == null
                || !pluginId.equals(after.identity.pluginId)
                || before != null && !pluginId.equals(before.identity.pluginId)
                || !isValidTransition(pluginId, before, after, reason, rotationProof)) {
            throw new IllegalArgumentException("Invalid persisted signer journal");
        }

        this.pluginId = pluginId;
        this.before = before;
        this.after = after;
        this.reason = reason;
        this.rotationProof = rotationProof;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, pluginId, 100);
            output.writeInt(reasonCode(reason));
            output.writeByte(before == null ? 0 : 1);

            if (before != null) {
                writeState(output, before);
            }

            writeState(output, after);
            output.writeByte(rotationProof == null ? 0 : 1);

            if (rotationProof != null) {
                writeProof(output, rotationProof);
            }
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Persisted signer journal exceeds size limit");
        }

        return encoded;
    }

    public static PluginSignerJournal decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid persisted signer journal size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid persisted signer journal header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported persisted signer journal version");
            }

            String pluginId = readString(input, 100);
            PluginSignerTransition.Reason reason = reason(input.readInt());
            int hasBefore = input.readUnsignedByte();

            if (hasBefore > 1) {
                throw new IOException("Invalid persisted signer journal baseline flag");
            }

            PluginSignerState before = hasBefore == 0 ? null : readState(input);
            PluginSignerState after = readState(input);
            int hasProof = input.readUnsignedByte();

            if (hasProof > 1) {
                throw new IOException("Invalid persisted signer journal proof flag");
            }

            PluginKeyRotationProof rotationProof = hasProof == 0 ? null : readProof(input);

            if (input.read() != -1) {
                throw new IOException("Trailing persisted signer journal data");
            }

            return new PluginSignerJournal(pluginId, before, after, reason, rotationProof);
        } catch (EOFException error) {
            throw new IOException("Truncated persisted signer journal", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid persisted signer journal content", error);
        }
    }

    private static boolean isValidTransition(
            String pluginId,
            PluginSignerState before,
            PluginSignerState after,
            PluginSignerTransition.Reason reason,
            PluginKeyRotationProof rotationProof) {
        if (reason == null) {
            return false;
        }
        if (reason != PluginSignerTransition.Reason.VERIFIED_ROTATION && rotationProof != null) {
            return false;
        }

        switch (reason) {
            case PIN_FIRST_IDENTITY:
                return before == null
                        && (after.identity.kind == PluginSignerIdentity.Kind.UNSIGNED
                                || after.identity.generation == 0);
            case SAME_IDENTITY:
                return before != null && before.identity.equals(after.identity);
            case UNSIGNED_PACKAGE_CHANGED:
                return before != null
                        && before.identity.kind == PluginSignerIdentity.Kind.UNSIGNED
                        && after.identity.kind == PluginSignerIdentity.Kind.UNSIGNED
                        && !before.archiveSha256.equals(after.archiveSha256);
            case UPGRADE_UNSIGNED_TO_SIGNED:
                return before != null
                        && before.identity.kind == PluginSignerIdentity.Kind.UNSIGNED
                        && after.identity.kind == PluginSignerIdentity.Kind.SIGNED
                        && after.identity.generation == 0
                        && !before.archiveSha256.equals(after.archiveSha256);
            case VERIFIED_ROTATION:
                return before != null
                        && before.identity.kind == PluginSignerIdentity.Kind.SIGNED
                        && after.identity.kind == PluginSignerIdentity.Kind.SIGNED
                        && !before.identity.keyId.equals(after.identity.keyId)
                        && !before.archiveSha256.equals(after.archiveSha256)
                        && before.identity.generation < Long.MAX_VALUE
                        && after.identity.generation == before.identity.generation + 1
                        && isMatchingProof(pluginId, before, after, rotationProof);
            default:
                return false;
        }
    }

    private static boolean isMatchingProof(
            String pluginId,
            PluginSignerState before,
            PluginSignerState after,
            PluginKeyRotationProof proof) {
        return proof != null
                && proof.pluginId.equals(pluginId)
                && proof.fromKeyId.equals(before.identity.keyId)
                && proof.toKeyId.equals(after.identity.keyId)
                && proof.fromGeneration == before.identity.generation
                && proof.toGeneration == after.identity.generation
                && proof.verify().isVerified();
    }

    private static int reasonCode(PluginSignerTransition.Reason reason) {
        switch (reason) {
            case PIN_FIRST_IDENTITY:
                return 1;
            case SAME_IDENTITY:
                return 2;
            case UNSIGNED_PACKAGE_CHANGED:
                return 3;
            case UPGRADE_UNSIGNED_TO_SIGNED:
                return 4;
            case VERIFIED_ROTATION:
                return 5;
            default:
                throw new IllegalArgumentException("Signer journal cannot persist blocked transition");
        }
    }

    private static PluginSignerTransition.Reason reason(int code) throws IOException {
        switch (code) {
            case 1:
                return PluginSignerTransition.Reason.PIN_FIRST_IDENTITY;
            case 2:
                return PluginSignerTransition.Reason.SAME_IDENTITY;
            case 3:
                return PluginSignerTransition.Reason.UNSIGNED_PACKAGE_CHANGED;
            case 4:
                return PluginSignerTransition.Reason.UPGRADE_UNSIGNED_TO_SIGNED;
            case 5:
                return PluginSignerTransition.Reason.VERIFIED_ROTATION;
            default:
                throw new IOException("Unknown persisted signer journal reason");
        }
    }

    private static void writeState(DataOutputStream output, PluginSignerState state)
            throws IOException {
        byte[] encoded = state.encode();
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static PluginSignerState readState(DataInputStream input) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > MAX_STATE_BYTES) {
            throw new IOException("Invalid persisted signer journal state length");
        }

        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return PluginSignerState.decode(encoded);
    }

    private static void writeProof(DataOutputStream output, PluginKeyRotationProof proof)
            throws IOException {
        byte[] encoded = proof.encode();
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static PluginKeyRotationProof readProof(DataInputStream input) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > MAX_PROOF_BYTES) {
            throw new IOException("Invalid persisted signer journal proof length");
        }

        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return PluginKeyRotationProof.decode(encoded);
    }

    private static void writeString(DataOutputStream output, String value, int limit)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);

        if (encoded.length == 0 || encoded.length > limit) {
            throw new IOException("Invalid persisted signer journal string");
        }

        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid persisted signer journal string length");
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
            throw new IOException("Invalid persisted signer journal UTF-8", error);
        }
    }
}
