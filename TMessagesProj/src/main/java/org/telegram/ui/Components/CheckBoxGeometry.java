package org.telegram.ui.Components;

public final class CheckBoxGeometry {
    private final float containerSizeDp;
    private final float cornerRadiusDp;
    private final float outlineWidthDp;
    private final float transitionScale;
    private final long checkDurationMs;
    private final long uncheckDurationMs;
    private final long fadeDurationMs;

    public CheckBoxGeometry(
            float containerSizeDp,
            float cornerRadiusDp,
            float outlineWidthDp,
            float transitionScale,
            long checkDurationMs,
            long uncheckDurationMs,
            long fadeDurationMs
    ) {
        if (containerSizeDp <= 0
                || cornerRadiusDp < 0
                || outlineWidthDp <= 0
                || transitionScale <= 0
                || transitionScale > 1
                || checkDurationMs <= 0
                || uncheckDurationMs <= 0
                || fadeDurationMs <= 0) {
            throw new IllegalArgumentException();
        }

        this.containerSizeDp = containerSizeDp;
        this.cornerRadiusDp = cornerRadiusDp;
        this.outlineWidthDp = outlineWidthDp;
        this.transitionScale = transitionScale;
        this.checkDurationMs = checkDurationMs;
        this.uncheckDurationMs = uncheckDurationMs;
        this.fadeDurationMs = fadeDurationMs;
    }

    public float getContainerSizeDp() {
        return containerSizeDp;
    }

    public float getCornerRadiusDp() {
        return cornerRadiusDp;
    }

    public float getOutlineWidthDp() {
        return outlineWidthDp;
    }

    public float getTransitionScale() {
        return transitionScale;
    }

    public long getCheckDurationMs() {
        return checkDurationMs;
    }

    public long getUncheckDurationMs() {
        return uncheckDurationMs;
    }

    public long getFadeDurationMs() {
        return fadeDurationMs;
    }
}
