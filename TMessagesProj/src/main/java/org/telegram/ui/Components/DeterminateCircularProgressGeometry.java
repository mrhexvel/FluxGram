package org.telegram.ui.Components;

public final class DeterminateCircularProgressGeometry {
    private final int diameterDp;
    private final float trackThicknessDp;
    private final float trackGapDp;
    private final float waveAmplitudeDp;
    private final float wavelengthDp;

    public DeterminateCircularProgressGeometry(
            int diameterDp,
            float trackThicknessDp,
            float trackGapDp,
            float waveAmplitudeDp,
            float wavelengthDp
    ) {
        if (diameterDp <= 0
                || trackThicknessDp <= 0
                || trackThicknessDp * 2.0f > diameterDp
                || trackGapDp < 0
                || waveAmplitudeDp <= 0
                || wavelengthDp <= 0) {
            throw new IllegalArgumentException();
        }

        this.diameterDp = diameterDp;
        this.trackThicknessDp = trackThicknessDp;
        this.trackGapDp = trackGapDp;
        this.waveAmplitudeDp = waveAmplitudeDp;
        this.wavelengthDp = wavelengthDp;
    }

    public int getDiameterDp() {
        return diameterDp;
    }

    public float getTrackThicknessDp() {
        return trackThicknessDp;
    }

    public float getTrackGapDp() {
        return trackGapDp;
    }

    public float getWaveAmplitudeDp() {
        return waveAmplitudeDp;
    }

    public float getWavelengthDp() {
        return wavelengthDp;
    }
}
