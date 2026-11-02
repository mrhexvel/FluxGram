package org.telegram.messenger.plugins.reputation;

public final class PluginReputationInstallPolicy {
    public enum Decision {
        WAIT,
        ALLOW,
        BLOCK
    }

    private PluginReputationInstallPolicy() {
    }

    public static Decision evaluate(PluginReputationCoordinator.Result result) {
        if (result == null) {
            throw new IllegalArgumentException("Plugin reputation result is required");
        }
        if (!result.terminal) {
            return Decision.WAIT;
        }
        if (result.lookup.authenticated
                && result.lookup.decision == PluginReputationPolicy.Decision.BLOCK) {
            return Decision.BLOCK;
        }

        return Decision.ALLOW;
    }
}
