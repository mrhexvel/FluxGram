package org.telegram.messenger.plugins.reputation;

import org.telegram.messenger.plugins.signing.PluginSignatureEnvelope;
import org.telegram.messenger.plugins.signing.PluginSignatureVerification;
import org.telegram.messenger.plugins.signing.PluginSignatureVerifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PluginReputationVerifier {
    private static final int MAGIC = 0x46475253;
    private static final int FORMAT_VERSION = 1;
    private static final String DOMAIN = "fluxgram.plugin.reputation.response";
    private static final int MAX_PUBLIC_KEY_BYTES = 256;

    private PluginReputationVerifier() {
    }

    public static PluginReputationVerification verifyEncoded(
            byte[] encodedResponse,
            byte[] encodedEnvelope,
            String authorityKeyId,
            String expectedArchiveSha256,
            String expectedDeveloperKeyId,
            long nowMillis) {
        if (!PluginReputationResponse.isKeyId(authorityKeyId)
                || !PluginReputationResponse.isSha256(expectedArchiveSha256)
                || expectedDeveloperKeyId != null
                        && !PluginReputationResponse.isKeyId(expectedDeveloperKeyId)
                || nowMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin reputation verification input");
        }

        PluginReputationResponse response;

        try {
            response = PluginReputationResponse.decode(encodedResponse);
        } catch (IOException error) {
            return result(PluginReputationVerification.Status.MALFORMED_RESPONSE, null, null, null);
        }

        PluginSignatureEnvelope envelope;

        try {
            envelope = PluginSignatureEnvelope.decode(encodedEnvelope);
        } catch (IOException error) {
            return result(PluginReputationVerification.Status.MALFORMED_ENVELOPE, null, null, null);
        }

        byte[] message;

        try {
            message = canonicalMessage(response, envelope.algorithm, envelope.publicKey());
        } catch (IOException error) {
            return result(PluginReputationVerification.Status.MALFORMED_RESPONSE, null, null, null);
        }

        PluginSignatureVerification signature =
                PluginSignatureVerifier.verifyCanonicalMessage(message, envelope);

        if (!signature.isVerified()) {
            return result(
                    mapSignatureStatus(signature.status),
                    null,
                    null,
                    signature.payloadSha256);
        }
        if (!authorityKeyId.equals(signature.keyId)) {
            return result(
                    PluginReputationVerification.Status.UNTRUSTED_AUTHORITY,
                    null,
                    signature.keyId,
                    signature.payloadSha256);
        }

        if (!expectedArchiveSha256.equals(response.archiveSha256)
                || !Objects.equals(expectedDeveloperKeyId, response.developerKeyId)) {
            return result(
                    PluginReputationVerification.Status.IDENTITY_MISMATCH,
                    response,
                    signature.keyId,
                    signature.payloadSha256);
        }
        if (nowMillis < response.issuedAtMillis) {
            return result(
                    PluginReputationVerification.Status.NOT_YET_VALID,
                    response,
                    signature.keyId,
                    signature.payloadSha256);
        }
        if (nowMillis >= response.expiresAtMillis) {
            return result(
                    PluginReputationVerification.Status.EXPIRED,
                    response,
                    signature.keyId,
                    signature.payloadSha256);
        }

        return result(
                PluginReputationVerification.Status.VERIFIED,
                response,
                signature.keyId,
                signature.payloadSha256);
    }

    public static byte[] canonicalMessage(
            PluginReputationResponse response,
            int algorithm,
            byte[] publicKey) throws IOException {
        if (response == null
                || algorithm <= 0
                || publicKey == null
                || publicKey.length == 0
                || publicKey.length > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("Invalid plugin reputation signed message");
        }

        byte[] domain = DOMAIN.getBytes(StandardCharsets.UTF_8);
        byte[] encodedResponse = response.encode();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(domain.length);
            output.write(domain);
            output.writeInt(algorithm);
            output.writeInt(publicKey.length);
            output.write(publicKey);
            output.writeInt(encodedResponse.length);
            output.write(encodedResponse);
        }

        return bytes.toByteArray();
    }

    private static PluginReputationVerification.Status mapSignatureStatus(
            PluginSignatureVerification.Status status) {
        switch (status) {
            case UNSUPPORTED_ALGORITHM:
                return PluginReputationVerification.Status.UNSUPPORTED_ALGORITHM;
            case MALFORMED_PUBLIC_KEY:
                return PluginReputationVerification.Status.MALFORMED_PUBLIC_KEY;
            case MALFORMED_SIGNATURE:
                return PluginReputationVerification.Status.MALFORMED_SIGNATURE;
            case INVALID_SIGNATURE:
                return PluginReputationVerification.Status.INVALID_SIGNATURE;
            case PROVIDER_UNAVAILABLE:
                return PluginReputationVerification.Status.PROVIDER_UNAVAILABLE;
            case MISSING_SIGNATURE:
            case MALFORMED_ENVELOPE:
                return PluginReputationVerification.Status.MALFORMED_ENVELOPE;
            case VERIFIED:
            default:
                throw new IllegalStateException("Unexpected plugin signature status");
        }
    }

    private static PluginReputationVerification result(
            PluginReputationVerification.Status status,
            PluginReputationResponse response,
            String authorityKeyId,
            String payloadSha256) {
        return new PluginReputationVerification(
                status,
                response,
                authorityKeyId,
                payloadSha256);
    }
}
