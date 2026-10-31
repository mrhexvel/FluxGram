package org.telegram.messenger.plugins.signing;

public final class PluginSignatureVerification {
    public enum Status {
        VERIFIED,
        MISSING_SIGNATURE,
        MALFORMED_ENVELOPE,
        UNSUPPORTED_ALGORITHM,
        MALFORMED_PUBLIC_KEY,
        MALFORMED_SIGNATURE,
        INVALID_SIGNATURE,
        PROVIDER_UNAVAILABLE
    }

    public final Status status;
    public final String pluginId;
    public final String keyId;
    public final String payloadSha256;

    PluginSignatureVerification(
            Status status,
            String pluginId,
            String keyId,
            String payloadSha256) {
        this.status = status;
        this.pluginId = pluginId;
        this.keyId = keyId;
        this.payloadSha256 = payloadSha256;
    }

    public boolean isVerified() {
        return status == Status.VERIFIED;
    }
}
