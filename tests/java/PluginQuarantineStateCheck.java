package org.telegram.messenger.plugins;

public final class PluginQuarantineStateCheck {
    public static void main(String[] args) {
        PluginQuarantineState state = new PluginQuarantineState();

        state.record(2, PluginQuarantineReason.CALLBACK_FAILURE);
        require(state.get(2) == PluginQuarantineReason.CALLBACK_FAILURE);
        require(state.get(1) == null);

        state.record(2, PluginQuarantineReason.TIME_LIMIT);
        require(state.get(2) == PluginQuarantineReason.CALLBACK_FAILURE);

        state.record(2, PluginQuarantineReason.CLEANUP_FAILURE);
        require(state.get(2) == PluginQuarantineReason.CLEANUP_FAILURE);

        state.record(1, PluginQuarantineReason.CLEANUP_FAILURE);
        require(state.get(2) == PluginQuarantineReason.CLEANUP_FAILURE);

        state.record(3, PluginQuarantineReason.TIME_LIMIT);
        require(state.get(2) == null);
        require(state.get(3) == PluginQuarantineReason.TIME_LIMIT);
    }

    private static void require(boolean value) {
        if (!value) {
            throw new AssertionError();
        }
    }
}
