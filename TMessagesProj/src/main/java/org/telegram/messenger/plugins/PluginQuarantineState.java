package org.telegram.messenger.plugins;

final class PluginQuarantineState {
    private long generation = -1;
    private PluginQuarantineReason reason;

    synchronized void record(long generation, PluginQuarantineReason reason) {
        if (generation < 0 || reason == null || generation < this.generation
                || generation == this.generation && priority(reason) <= priority(this.reason)) {
            return;
        }

        this.generation = generation;
        this.reason = reason;
    }

    synchronized PluginQuarantineReason get(long generation) {
        return this.generation == generation ? reason : null;
    }

    private static int priority(PluginQuarantineReason reason) {
        if (reason == PluginQuarantineReason.CLEANUP_FAILURE) {
            return 3;
        }

        if (reason == PluginQuarantineReason.CALLBACK_FAILURE) {
            return 2;
        }

        return reason == PluginQuarantineReason.TIME_LIMIT ? 1 : 0;
    }
}
