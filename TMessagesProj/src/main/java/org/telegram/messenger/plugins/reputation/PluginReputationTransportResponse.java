package org.telegram.messenger.plugins.reputation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

public final class PluginReputationTransportResponse {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_RESPONSE_BYTES = 128;
    public static final int MAX_ENVELOPE_BYTES = 512;
    public static final int MAX_ENCODED_BYTES = 660;

    private static final int MAGIC = 0x46475254;

    private final byte[] response;
    private final byte[] envelope;

    public PluginReputationTransportResponse(byte[] response, byte[] envelope) {
        if (response == null
                || response.length == 0
                || response.length > MAX_RESPONSE_BYTES
                || envelope == null
                || envelope.length == 0
                || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Invalid plugin reputation transport response");
        }

        this.response = response.clone();
        this.envelope = envelope.clone();
    }

    public byte[] response() {
        return response.clone();
    }

    public byte[] envelope() {
        return envelope.clone();
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(0);
            output.writeInt(response.length);
            output.writeInt(envelope.length);
            output.write(response);
            output.write(envelope);
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Plugin reputation transport response exceeds size limit");
        }

        return encoded;
    }

    public static PluginReputationTransportResponse decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 22 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid plugin reputation transport response size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid plugin reputation transport response header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported plugin reputation transport response version");
            }
            if (input.readInt() != 0) {
                throw new IOException("Unknown plugin reputation transport response flags");
            }

            int responseLength = input.readInt();
            int envelopeLength = input.readInt();

            if (responseLength <= 0
                    || responseLength > MAX_RESPONSE_BYTES
                    || envelopeLength <= 0
                    || envelopeLength > MAX_ENVELOPE_BYTES
                    || responseLength + envelopeLength != encoded.length - 20) {
                throw new IOException("Invalid plugin reputation transport response lengths");
            }

            byte[] response = readBytes(input, responseLength);
            byte[] envelope = readBytes(input, envelopeLength);

            if (input.read() != -1) {
                throw new IOException("Trailing plugin reputation transport response data");
            }

            return new PluginReputationTransportResponse(response, envelope);
        } catch (EOFException error) {
            throw new IOException("Truncated plugin reputation transport response", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid plugin reputation transport response content", error);
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginReputationTransportResponse)) {
            return false;
        }

        PluginReputationTransportResponse other = (PluginReputationTransportResponse) object;
        return Arrays.equals(response, other.response)
                && Arrays.equals(envelope, other.envelope);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(response) + Arrays.hashCode(envelope);
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }
}
