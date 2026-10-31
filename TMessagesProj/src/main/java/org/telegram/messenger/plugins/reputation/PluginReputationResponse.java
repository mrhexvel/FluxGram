package org.telegram.messenger.plugins.reputation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

public final class PluginReputationResponse {
    public enum ArchiveStatus {
        CLEAR(1),
        MALICIOUS(2);

        private final int code;

        ArchiveStatus(int code) {
            this.code = code;
        }

        private static ArchiveStatus fromCode(int code) throws IOException {
            for (ArchiveStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }

            throw new IOException("Unknown plugin archive reputation status");
        }
    }

    public enum KeyStatus {
        NOT_APPLICABLE(0),
        CLEAR(1),
        REVOKED(2);

        private final int code;

        KeyStatus(int code) {
            this.code = code;
        }

        private static KeyStatus fromCode(int code) throws IOException {
            for (KeyStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }

            throw new IOException("Unknown plugin key reputation status");
        }
    }

    public static final int FORMAT_VERSION = 1;
    public static final long MAX_VALIDITY_MILLIS = 30L * 24 * 60 * 60 * 1000;

    private static final int MAGIC = 0x46475252;
    private static final int MAX_ENCODED_BYTES = 128;
    private static final int KEY_NONE = 0;
    private static final int KEY_P256 = 1;

    public final String archiveSha256;
    public final String developerKeyId;
    public final long revision;
    public final long issuedAtMillis;
    public final long expiresAtMillis;
    public final ArchiveStatus archiveStatus;
    public final KeyStatus keyStatus;

    public PluginReputationResponse(
            String archiveSha256,
            String developerKeyId,
            long revision,
            long issuedAtMillis,
            long expiresAtMillis,
            ArchiveStatus archiveStatus,
            KeyStatus keyStatus) {
        if (!isSha256(archiveSha256)
                || developerKeyId != null && !isKeyId(developerKeyId)
                || revision < 0
                || issuedAtMillis < 0
                || expiresAtMillis <= issuedAtMillis
                || expiresAtMillis - issuedAtMillis > MAX_VALIDITY_MILLIS
                || archiveStatus == null
                || keyStatus == null
                || developerKeyId == null && keyStatus != KeyStatus.NOT_APPLICABLE
                || developerKeyId != null && keyStatus == KeyStatus.NOT_APPLICABLE) {
            throw new IllegalArgumentException("Invalid plugin reputation response");
        }

        this.archiveSha256 = archiveSha256;
        this.developerKeyId = developerKeyId;
        this.revision = revision;
        this.issuedAtMillis = issuedAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.archiveStatus = archiveStatus;
        this.keyStatus = keyStatus;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.write(hexBytes(archiveSha256));

            if (developerKeyId == null) {
                output.writeInt(KEY_NONE);
            } else {
                output.writeInt(KEY_P256);
                output.write(hexBytes(developerKeyId.substring("p256:".length())));
            }

            output.writeLong(revision);
            output.writeLong(issuedAtMillis);
            output.writeLong(expiresAtMillis);
            output.writeInt(archiveStatus.code);
            output.writeInt(keyStatus.code);
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Plugin reputation response exceeds size limit");
        }

        return encoded;
    }

    public static PluginReputationResponse decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin reputation response size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin reputation response header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin reputation response version");
            }

            String archiveSha256 = hex(readBytes(input, 32));
            int keyKind = input.readInt();
            String developerKeyId;

            if (keyKind == KEY_NONE) {
                developerKeyId = null;
            } else if (keyKind == KEY_P256) {
                developerKeyId = "p256:" + hex(readBytes(input, 32));
            } else {
                throw new IOException("Unknown plugin reputation key kind");
            }

            long revision = input.readLong();
            long issuedAtMillis = input.readLong();
            long expiresAtMillis = input.readLong();
            ArchiveStatus archiveStatus = ArchiveStatus.fromCode(input.readInt());
            KeyStatus keyStatus = KeyStatus.fromCode(input.readInt());

            if (input.read() != -1) {
                throw new IOException("Trailing plugin reputation response data");
            }

            return new PluginReputationResponse(
                    archiveSha256,
                    developerKeyId,
                    revision,
                    issuedAtMillis,
                    expiresAtMillis,
                    archiveStatus,
                    keyStatus);
        } catch (EOFException error) {
            throw new IOException("Truncated plugin reputation response", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin reputation response content", error);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginReputationResponse)) {
            return false;
        }

        PluginReputationResponse other = (PluginReputationResponse) object;
        return revision == other.revision
                && issuedAtMillis == other.issuedAtMillis
                && expiresAtMillis == other.expiresAtMillis
                && archiveSha256.equals(other.archiveSha256)
                && Objects.equals(developerKeyId, other.developerKeyId)
                && archiveStatus == other.archiveStatus
                && keyStatus == other.keyStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                archiveSha256,
                developerKeyId,
                revision,
                issuedAtMillis,
                expiresAtMillis,
                archiveStatus,
                keyStatus);
    }

    static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);

            if ((character < '0' || character > '9')
                    && (character < 'a' || character > 'f')) {
                return false;
            }
        }

        return true;
    }

    static boolean isKeyId(String value) {
        return value != null
                && value.startsWith("p256:")
                && isSha256(value.substring("p256:".length()));
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
