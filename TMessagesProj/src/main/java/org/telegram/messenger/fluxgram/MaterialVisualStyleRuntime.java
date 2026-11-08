package org.telegram.messenger.fluxgram;

public final class MaterialVisualStyleRuntime {
    private static volatile MaterialVisualStyle.Snapshot snapshot = MaterialVisualStyle.telegram();
    private static boolean initialized;

    private MaterialVisualStyleRuntime() {
    }

    public static synchronized MaterialVisualStyle.Snapshot initialize(
            MaterialStylePolicy.EffectiveSnapshot effectiveSnapshot
    ) {
        if (!initialized) {
            snapshot = MaterialVisualStyle.resolve(effectiveSnapshot);
            initialized = true;
        }

        return snapshot;
    }

    public static MaterialVisualStyle.Snapshot getSnapshot() {
        return snapshot;
    }
}
