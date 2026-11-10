package org.telegram.ui.Components;

public final class LinearProgressGeometry {
    private final float trackThicknessDp;
    private final float trackGapDp;
    private final float thumbWidthDp;
    private final float draggedThumbWidthDp;
    private final float thumbHeightDp;
    private final float waveAmplitudeDp;
    private final float wavelengthDp;
    private final float stopIndicatorDiameterDp;
    private final int touchTargetHeightDp;

    public LinearProgressGeometry(
            float trackThicknessDp,
            float trackGapDp,
            float thumbWidthDp,
            float draggedThumbWidthDp,
            float thumbHeightDp,
            float waveAmplitudeDp,
            float wavelengthDp,
            float stopIndicatorDiameterDp,
            int touchTargetHeightDp
    ) {
        if (trackThicknessDp <= 0
                || trackGapDp < 0
                || thumbWidthDp <= 0
                || draggedThumbWidthDp <= 0
                || thumbHeightDp < trackThicknessDp
                || waveAmplitudeDp <= 0
                || wavelengthDp <= 0
                || stopIndicatorDiameterDp <= 0
                || touchTargetHeightDp < trackThicknessDp + waveAmplitudeDp * 2.0f) {
            throw new IllegalArgumentException();
        }

        this.trackThicknessDp = trackThicknessDp;
        this.trackGapDp = trackGapDp;
        this.thumbWidthDp = thumbWidthDp;
        this.draggedThumbWidthDp = draggedThumbWidthDp;
        this.thumbHeightDp = thumbHeightDp;
        this.waveAmplitudeDp = waveAmplitudeDp;
        this.wavelengthDp = wavelengthDp;
        this.stopIndicatorDiameterDp = stopIndicatorDiameterDp;
        this.touchTargetHeightDp = touchTargetHeightDp;
    }

    public float getTrackThicknessDp() {
        return trackThicknessDp;
    }

    public float getTrackGapDp() {
        return trackGapDp;
    }

    public float getThumbWidthDp() {
        return thumbWidthDp;
    }

    public float getDraggedThumbWidthDp() {
        return draggedThumbWidthDp;
    }

    public float getThumbHeightDp() {
        return thumbHeightDp;
    }

    public float getWaveAmplitudeDp() {
        return waveAmplitudeDp;
    }

    public float getWavelengthDp() {
        return wavelengthDp;
    }

    public float getStopIndicatorDiameterDp() {
        return stopIndicatorDiameterDp;
    }

    public int getTouchTargetHeightDp() {
        return touchTargetHeightDp;
    }

    public void resolve(
            float width,
            float density,
            float progress,
            boolean rtl,
            Resolved output
    ) {
        if (width < 0 || density <= 0 || output == null) {
            throw new IllegalArgumentException();
        }

        float boundedProgress = Math.max(0.0f, Math.min(progress, 1.0f));
        float progressX = width * (rtl ? 1.0f - boundedProgress : boundedProgress);
        float thumbHalfWidth = thumbWidthDp * density / 2.0f;
        float gap = trackGapDp * density + thumbHalfWidth;

        if (rtl) {
            output.activeStart = Math.min(width, progressX + gap);
            output.activeEnd = width;
            output.inactiveStart = 0.0f;
            output.inactiveEnd = Math.max(0.0f, progressX - gap);
        } else {
            output.activeStart = 0;
            output.activeEnd = Math.max(0.0f, progressX - gap);
            output.inactiveStart = Math.min(width, progressX + gap);
            output.inactiveEnd = width;
        }

        output.thumbCenter = progressX;
    }

    public static final class Resolved {
        private float activeStart;
        private float activeEnd;
        private float inactiveStart;
        private float inactiveEnd;
        private float thumbCenter;

        public float getActiveStart() {
            return activeStart;
        }

        public float getActiveEnd() {
            return activeEnd;
        }

        public float getInactiveStart() {
            return inactiveStart;
        }

        public float getInactiveEnd() {
            return inactiveEnd;
        }

        public float getThumbCenter() {
            return thumbCenter;
        }
    }
}
