package org.telegram.messenger.plugins.reputation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

public final class PluginReputationQuery {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x46475251;
    private static final int FLAG_DEVELOPER_KEY = 1;
    private static final int MAX_ENCODED_BYTES = 76;

    public final String archiveSha256;
    public final String developerKeyId;

    public PluginReputationQuery(String archiveSha256, String developerKeyId) {
        if (!PluginReputationResponse.isSha256(archiveSha256)
                || developerKeyId != null && !PluginReputationResponse.isKeyId(developerKeyId)) {
            throw new IllegalArgumentException("Invalid plugin reputation query");
        }

        this.archiveSha256 = archiveSha256;
        this.developerKeyId = developerKeyId;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(developerKeyId == null ? 0 : FLAG_DEVELOPER_KEY);
            output.write(hexBytes(archiveSha256));

            if (developerKeyId != null) {
                output.write(hexBytes(developerKeyId.substring("p256:".length())));
            }
        }

        return bytes.toByteArray();
    }

    public static PluginReputationQuery decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin reputation query size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin reputation query header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin reputation query version");
            }

            int flags = input.readInt();

            if ((flags & ~FLAG_DEVELOPER_KEY) != 0) {
                throw new IOException("Unknown plugin reputation query flags");
            }

            String archiveSha256 = hex(readBytes(input, 32));
            String developerKeyId = null;

            if ((flags & FLAG_DEVELOPER_KEY) != 0) {
                developerKeyId = "p256:" + hex(readBytes(input, 32));
            }
            if (input.read() != -1) {
                throw new IOException("Trailing plugin reputation query data");
            }

            return new PluginReputationQuery(archiveSha256, developerKeyId);
        } catch (EOFException error) {
            throw new IOException("Truncated plugin reputation query", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin reputation query content", error);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginReputationQuery)) {
            return false;
        }

        PluginReputationQuery other = (PluginReputationQuery) object;
        return archiveSha256.equals(other.archiveSha256)
                && Objects.equals(developerKeyId, other.developerKeyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(archiveSha256, developerKeyId);
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
