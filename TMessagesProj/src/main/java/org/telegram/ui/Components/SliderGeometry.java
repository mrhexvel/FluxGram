package org.telegram.ui.Components;

public final class SliderGeometry {
    private final float trackHeightDp;
    private final float thumbWidthDp;
    private final float thumbHeightDp;
    private final float thumbTrackGapDp;
    private final float trackInsideCornerRadiusDp;
    private final float stopIndicatorSizeDp;
    private final float pressedThumbWidthRatio;

    public SliderGeometry(
            float trackHeightDp,
            float thumbWidthDp,
            float thumbHeightDp,
            float thumbTrackGapDp,
            float trackInsideCornerRadiusDp,
            float stopIndicatorSizeDp,
            float pressedThumbWidthRatio
    ) {
        if (trackHeightDp <= 0
                || thumbWidthDp <= 0
                || thumbHeightDp <= 0
                || thumbTrackGapDp < 0
                || trackInsideCornerRadiusDp < 0
                || trackInsideCornerRadiusDp > trackHeightDp / 2.0f
                || stopIndicatorSizeDp < 0
                || stopIndicatorSizeDp > trackHeightDp
                || pressedThumbWidthRatio <= 0
                || pressedThumbWidthRatio > 1) {
            throw new IllegalArgumentException();
        }

        this.trackHeightDp = trackHeightDp;
        this.thumbWidthDp = thumbWidthDp;
        this.thumbHeightDp = thumbHeightDp;
        this.thumbTrackGapDp = thumbTrackGapDp;
        this.trackInsideCornerRadiusDp = trackInsideCornerRadiusDp;
        this.stopIndicatorSizeDp = stopIndicatorSizeDp;
        this.pressedThumbWidthRatio = pressedThumbWidthRatio;
    }

    public float getTrackHeightDp() {
        return trackHeightDp;
    }

    public float getThumbWidthDp() {
        return thumbWidthDp;
    }

    public float getThumbHeightDp() {
        return thumbHeightDp;
    }

    public float getThumbTrackGapDp() {
        return thumbTrackGapDp;
    }

    public float getTrackInsideCornerRadiusDp() {
        return trackInsideCornerRadiusDp;
    }

    public float getStopIndicatorSizeDp() {
        return stopIndicatorSizeDp;
    }

    public float getPressedThumbWidthRatio() {
        return pressedThumbWidthRatio;
    }
}
