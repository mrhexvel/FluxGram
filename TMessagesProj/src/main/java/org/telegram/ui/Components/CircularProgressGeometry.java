package org.telegram.ui.Components;

public final class CircularProgressGeometry {
    private final int diameterDp;
    private final int trackThicknessDp;
    private final int trackGapDp;
    private final float waveAmplitudeDp;
    private final int wavelengthDp;
    private final int totalDurationMs;

    public CircularProgressGeometry(
            int diameterDp,
            int trackThicknessDp,
            int trackGapDp,
            float waveAmplitudeDp,
            int wavelengthDp,
            int totalDurationMs
    ) {
        if (diameterDp <= 0
                || trackThicknessDp <= 0
                || trackThicknessDp * 2 > diameterDp
                || trackGapDp < 0
                || waveAmplitudeDp <= 0
                || wavelengthDp <= 0
                || totalDurationMs <= 0) {
            throw new IllegalArgumentException();
        }

        this.diameterDp = diameterDp;
        this.trackThicknessDp = trackThicknessDp;
        this.trackGapDp = trackGapDp;
        this.waveAmplitudeDp = waveAmplitudeDp;
        this.wavelengthDp = wavelengthDp;
        this.totalDurationMs = totalDurationMs;
    }

    public int getDiameterDp() {
        return diameterDp;
    }

    public int getTrackThicknessDp() {
        return trackThicknessDp;
    }

    public int getTrackGapDp() {
        return trackGapDp;
    }

    public float getWaveAmplitudeDp() {
        return waveAmplitudeDp;
    }

    public int getWavelengthDp() {
        return wavelengthDp;
    }

    public int getTotalDurationMs() {
        return totalDurationMs;
    }

    public void resolve(long playTimeMs, Resolved output) {
        if (output == null) {
            throw new IllegalArgumentException();
        }

        int playTime = (int) Math.floorMod(playTimeMs, totalDurationMs);
        float animationFraction = playTime / (float) totalDurationMs;
        float rotationDegrees = 1080.0f * animationFraction;

        for (int delay = 0; delay <= 4500; delay += 1500) {
            rotationDegrees += fastOutSlowIn(fraction(playTime, delay, 500)) * 90.0f;
        }

        float activeFraction = fastOutSlowIn(fraction(playTime, 0, 3000))
                - fastOutSlowIn(fraction(playTime, 3000, 3000));
        output.rotationDegrees = rotationDegrees;
        output.endFraction = 0.10f + 0.77f * activeFraction;
    }

    private static float fraction(int playTimeMs, int delayMs, int durationMs) {
        return Math.max(0.0f, Math.min(1.0f,
                (playTimeMs - delayMs) / (float) durationMs));
    }

    private static float fastOutSlowIn(float input) {
        float time = input;

        for (int i = 0; i < 13; i++) {
            float x = cubic(time, 0.4f, 0.2f) - input;

            if (Math.abs(x) < 0.001f) {
                break;
            }

            float derivative = cubicDerivative(time, 0.4f, 0.2f);

            if (Math.abs(derivative) < 0.0001f) {
                break;
            }

            time -= x / derivative;
        }

        return cubic(time, 0.0f, 1.0f);
    }

    private static float cubic(float time, float first, float second) {
        float oneMinusTime = 1.0f - time;
        return 3.0f * oneMinusTime * oneMinusTime * time * first
                + 3.0f * oneMinusTime * time * time * second
                + time * time * time;
    }

    private static float cubicDerivative(float time, float first, float second) {
        float oneMinusTime = 1.0f - time;
        return 3.0f * oneMinusTime * oneMinusTime * first
                + 6.0f * oneMinusTime * time * (second - first)
                + 3.0f * time * time * (1.0f - second);
    }

    public static final class Resolved {
        private float rotationDegrees;
        private float endFraction;

        public float getRotationDegrees() {
            return rotationDegrees;
        }

        public float getEndFraction() {
            return endFraction;
        }
    }
}
