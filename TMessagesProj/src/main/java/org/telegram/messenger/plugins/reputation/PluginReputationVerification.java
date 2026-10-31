package org.telegram.messenger.plugins.reputation;

public final class PluginReputationVerification {
    public enum Status {
        VERIFIED,
        MALFORMED_RESPONSE,
        MALFORMED_ENVELOPE,
        UNSUPPORTED_ALGORITHM,
        MALFORMED_PUBLIC_KEY,
        MALFORMED_SIGNATURE,
        INVALID_SIGNATURE,
        PROVIDER_UNAVAILABLE,
        UNTRUSTED_AUTHORITY,
        IDENTITY_MISMATCH,
        NOT_YET_VALID,
        EXPIRED
    }

    public final Status status;
    public final PluginReputationResponse response;
    public final String authorityKeyId;
    public final String payloadSha256;

    PluginReputationVerification(
            Status status,
            PluginReputationResponse response,
            String authorityKeyId,
            String payloadSha256) {
        this.status = status;
        this.response = response;
        this.authorityKeyId = authorityKeyId;
        this.payloadSha256 = payloadSha256;
    }

    public boolean isAuthenticated() {
        return status == Status.VERIFIED
                || status == Status.IDENTITY_MISMATCH
                || status == Status.NOT_YET_VALID
                || status == Status.EXPIRED;
    }

    public boolean isFresh() {
        return status == Status.VERIFIED;
    }
}
