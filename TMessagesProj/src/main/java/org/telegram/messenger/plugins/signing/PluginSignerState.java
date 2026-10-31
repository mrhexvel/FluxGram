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
import java.util.Objects;

public final class PluginSignerState {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x46475353;
    private static final int MAX_ENCODED_BYTES = 256;
    private static final int KIND_UNSIGNED = 1;
    private static final int KIND_SIGNED = 2;

    public final String archiveSha256;
    public final PluginSignerIdentity identity;

    public PluginSignerState(String archiveSha256, PluginSignerIdentity identity) {
        if (!PluginSignerIdentity.isSha256(archiveSha256)
                || identity == null
                || identity.kind == PluginSignerIdentity.Kind.UNSIGNED
                        && !archiveSha256.equals(identity.packageSha256)) {
            throw new IllegalArgumentException("Invalid persisted signer state");
        }

        this.archiveSha256 = archiveSha256;
        this.identity = identity;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, identity.pluginId, 100);
            output.write(hexBytes(archiveSha256));

            if (identity.kind == PluginSignerIdentity.Kind.UNSIGNED) {
                output.writeInt(KIND_UNSIGNED);
            } else {
                output.writeInt(KIND_SIGNED);
                output.write(hexBytes(identity.keyId.substring("p256:".length())));
                output.writeLong(identity.generation);
            }
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Persisted signer state exceeds size limit");
        }

        return encoded;
    }

    public static PluginSignerState decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid persisted signer state size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid persisted signer state header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported persisted signer state version");
            }

            String pluginId = readString(input, 100);
            String archiveSha256 = hex(readBytes(input, 32));
            int kind = input.readInt();
            PluginSignerIdentity identity;

            if (kind == KIND_UNSIGNED) {
                identity = PluginSignerIdentity.unsigned(pluginId, archiveSha256);
            } else if (kind == KIND_SIGNED) {
                String keyId = "p256:" + hex(readBytes(input, 32));
                long generation = input.readLong();
                identity = PluginSignerIdentity.signed(pluginId, keyId, generation);
            } else {
                throw new IOException("Unknown persisted signer state kind");
            }

            if (input.read() != -1) {
                throw new IOException("Trailing persisted signer state data");
            }

            return new PluginSignerState(archiveSha256, identity);
        } catch (EOFException error) {
            throw new IOException("Truncated persisted signer state", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid persisted signer state content", error);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginSignerState)) {
            return false;
        }

        PluginSignerState other = (PluginSignerState) object;
        return archiveSha256.equals(other.archiveSha256) && identity.equals(other.identity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(archiveSha256, identity);
    }

    private static void writeString(DataOutputStream output, String value, int limit)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);

        if (encoded.length == 0 || encoded.length > limit) {
            throw new IOException("Invalid persisted signer state string");
        }

        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid persisted signer state string length");
        }

        byte[] encoded = readBytes(input, length);

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid persisted signer state UTF-8", error);
        }
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }

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
