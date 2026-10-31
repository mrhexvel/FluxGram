package org.telegram.messenger.plugins.reputation;

public final class PluginReputationState {
    public final PluginReputationResponse response;
    public final String authorityKeyId;

    private PluginReputationState(
            PluginReputationResponse response,
            String authorityKeyId) {
        this.response = response;
        this.authorityKeyId = authorityKeyId;
    }

    public static PluginReputationState update(
            PluginReputationState current,
            PluginReputationVerification candidate) {
        if (candidate == null
                || candidate.response == null
                || candidate.authorityKeyId == null
                || candidate.status != PluginReputationVerification.Status.VERIFIED
                        && candidate.status != PluginReputationVerification.Status.EXPIRED) {
            return current;
        }

        if (current == null) {
            return new PluginReputationState(candidate.response, candidate.authorityKeyId);
        }

        if (!current.authorityKeyId.equals(candidate.authorityKeyId)
                || !current.response.archiveSha256.equals(candidate.response.archiveSha256)
                || !java.util.Objects.equals(
                        current.response.developerKeyId,
                        candidate.response.developerKeyId)
                || candidate.response.revision < current.response.revision
                || candidate.response.revision == current.response.revision
                        && !candidate.response.equals(current.response)) {
            return current;
        }

        return new PluginReputationState(candidate.response, candidate.authorityKeyId);
    }
}
