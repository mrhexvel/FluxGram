package org.telegram.messenger.plugins.signing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

public final class PluginSignatureEnvelope {
    public static final int FORMAT_VERSION = 1;
    public static final int ALGORITHM_ECDSA_P256_SHA256 = 1;

    private static final int MAGIC = 0x46475345;
    private static final int MAX_ENCODED_BYTES = 512;
    private static final int MAX_PUBLIC_KEY_BYTES = 256;
    private static final int MAX_SIGNATURE_BYTES = 128;

    public final int algorithm;
    private final byte[] publicKey;
    private final byte[] signature;

    public PluginSignatureEnvelope(int algorithm, byte[] publicKey, byte[] signature) {
        if (algorithm <= 0
                || publicKey == null
                || publicKey.length == 0
                || publicKey.length > MAX_PUBLIC_KEY_BYTES
                || signature == null
                || signature.length == 0
                || signature.length > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Invalid plugin signature envelope");
        }

        this.algorithm = algorithm;
        this.publicKey = publicKey.clone();
        this.signature = signature.clone();
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public byte[] signature() {
        return signature.clone();
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(algorithm);
            writeBytes(output, publicKey);
            writeBytes(output, signature);
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Plugin signature envelope exceeds size limit");
        }

        return encoded;
    }

    public static PluginSignatureEnvelope decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin signature envelope size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin signature envelope header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin signature envelope version");
            }

            int algorithm = input.readInt();
            byte[] publicKey = readBytes(input, MAX_PUBLIC_KEY_BYTES);
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);

            if (input.read() != -1) {
                throw new IOException("Trailing plugin signature envelope data");
            }

            return new PluginSignatureEnvelope(algorithm, publicKey, signature);
        } catch (EOFException error) {
            throw new IOException("Truncated plugin signature envelope", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin signature envelope content", error);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginSignatureEnvelope)) {
            return false;
        }

        PluginSignatureEnvelope other = (PluginSignatureEnvelope) object;
        return algorithm == other.algorithm
                && Arrays.equals(publicKey, other.publicKey)
                && Arrays.equals(signature, other.signature);
    }

    @Override
    public int hashCode() {
        int result = algorithm;
        result = 31 * result + Arrays.hashCode(publicKey);
        return 31 * result + Arrays.hashCode(signature);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int limit) throws IOException {
        int length = input.readInt();

        if (length <= 0 || length > limit) {
            throw new IOException("Invalid plugin signature envelope field length");
        }

        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }
}
