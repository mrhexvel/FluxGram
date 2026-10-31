package org.telegram.messenger.plugins.signing;

public final class PluginSignerTransition {
    public enum Decision {
        ACCEPT,
        CONFIRM,
        BLOCK
    }

    public enum Reason {
        PIN_FIRST_IDENTITY,
        SAME_IDENTITY,
        UNSIGNED_PACKAGE_CHANGED,
        UPGRADE_UNSIGNED_TO_SIGNED,
        VERIFIED_ROTATION,
        INVALID_SIGNATURE,
        UNSIGNED_DOWNGRADE,
        KEY_CHANGED,
        INVALID_ROTATION,
        PLUGIN_ID_MISMATCH
    }

    public static final class Result {
        public final Decision decision;
        public final Reason reason;
        public final PluginSignerIdentity nextIdentity;

        Result(Decision decision, Reason reason, PluginSignerIdentity nextIdentity) {
            this.decision = decision;
            this.reason = reason;
            this.nextIdentity = nextIdentity;
        }
    }

    private PluginSignerTransition() {
    }

    public static Result evaluate(
            String pluginId,
            String packageSha256,
            PluginSignerIdentity pinned,
            PluginSignatureVerification candidate,
            PluginKeyRotationProof rotation) {
        if (!PluginSignerIdentity.isPluginId(pluginId)
                || !PluginSignerIdentity.isSha256(packageSha256)
                || candidate == null) {
            throw new IllegalArgumentException("Invalid signer transition input");
        }

        boolean unsigned = candidate.status == PluginSignatureVerification.Status.MISSING_SIGNATURE;
        boolean signed = candidate.isVerified();

        if (!unsigned && !signed) {
            return result(Decision.BLOCK, Reason.INVALID_SIGNATURE, pinned);
        }
        if (!pluginId.equals(candidate.pluginId)) {
            return result(Decision.BLOCK, Reason.PLUGIN_ID_MISMATCH, pinned);
        }

        if (pinned == null) {
            PluginSignerIdentity identity = unsigned
                    ? PluginSignerIdentity.unsigned(pluginId, packageSha256)
                    : PluginSignerIdentity.signed(pluginId, candidate.keyId, 0);
            return result(Decision.CONFIRM, Reason.PIN_FIRST_IDENTITY, identity);
        }
        if (!pluginId.equals(pinned.pluginId)) {
            return result(Decision.BLOCK, Reason.PLUGIN_ID_MISMATCH, pinned);
        }

        if (pinned.kind == PluginSignerIdentity.Kind.UNSIGNED) {
            if (unsigned) {
                if (pinned.packageSha256.equals(packageSha256)) {
                    return result(Decision.ACCEPT, Reason.SAME_IDENTITY, pinned);
                }

                return result(
                        Decision.CONFIRM,
                        Reason.UNSIGNED_PACKAGE_CHANGED,
                        PluginSignerIdentity.unsigned(pluginId, packageSha256));
            }

            return result(
                    Decision.CONFIRM,
                    Reason.UPGRADE_UNSIGNED_TO_SIGNED,
                    PluginSignerIdentity.signed(pluginId, candidate.keyId, 0));
        }

        if (unsigned) {
            return result(Decision.BLOCK, Reason.UNSIGNED_DOWNGRADE, pinned);
        }
        if (pinned.keyId.equals(candidate.keyId)) {
            return result(Decision.ACCEPT, Reason.SAME_IDENTITY, pinned);
        }
        if (rotation == null) {
            return result(Decision.BLOCK, Reason.KEY_CHANGED, pinned);
        }
        if (!rotation.pluginId.equals(pluginId)
                || !rotation.fromKeyId.equals(pinned.keyId)
                || !rotation.toKeyId.equals(candidate.keyId)
                || rotation.fromGeneration != pinned.generation) {
            return result(Decision.BLOCK, Reason.INVALID_ROTATION, pinned);
        }

        PluginKeyRotationProof.Verification verification = rotation.verify();

        if (!verification.isVerified()) {
            return result(Decision.BLOCK, Reason.INVALID_ROTATION, pinned);
        }

        return result(
                Decision.ACCEPT,
                Reason.VERIFIED_ROTATION,
                PluginSignerIdentity.signed(
                        pluginId,
                        candidate.keyId,
                        rotation.toGeneration));
    }

    private static Result result(
            Decision decision,
            Reason reason,
            PluginSignerIdentity nextIdentity) {
        return new Result(decision, reason, nextIdentity);
    }
}
