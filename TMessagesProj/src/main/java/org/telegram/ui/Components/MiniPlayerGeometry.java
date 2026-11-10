package org.telegram.ui.Components;

public final class MiniPlayerGeometry {
    private final int heightDp;
    private final int horizontalMarginDp;
    private final int verticalMarginDp;
    private final int cornerRadiusDp;
    private final int coverSizeDp;
    private final int coverRadiusDp;
    private final int controlButtonWidthDp;
    private final int playButtonWidthDp;
    private final int topGapDp;
    private final DeterminateCircularProgressGeometry progressGeometry;

    public MiniPlayerGeometry(
            int heightDp,
            int horizontalMarginDp,
            int verticalMarginDp,
            int cornerRadiusDp,
            int coverSizeDp,
            int coverRadiusDp,
            int controlButtonWidthDp,
            int playButtonWidthDp,
            int topGapDp,
            DeterminateCircularProgressGeometry progressGeometry
    ) {
        if (heightDp <= 0
                || horizontalMarginDp < 0
                || verticalMarginDp < 0
                || cornerRadiusDp <= 0
                || coverSizeDp <= 0
                || coverSizeDp > heightDp
                || coverRadiusDp < 0
                || coverRadiusDp > coverSizeDp / 2
                || controlButtonWidthDp <= 0
                || playButtonWidthDp <= 0
                || topGapDp < 0
                || progressGeometry == null) {
            throw new IllegalArgumentException();
        }

        this.heightDp = heightDp;
        this.horizontalMarginDp = horizontalMarginDp;
        this.verticalMarginDp = verticalMarginDp;
        this.cornerRadiusDp = cornerRadiusDp;
        this.coverSizeDp = coverSizeDp;
        this.coverRadiusDp = coverRadiusDp;
        this.controlButtonWidthDp = controlButtonWidthDp;
        this.playButtonWidthDp = playButtonWidthDp;
        this.topGapDp = topGapDp;
        this.progressGeometry = progressGeometry;
    }

    public int getHeightDp() {
        return heightDp;
    }

    public int getHorizontalMarginDp() {
        return horizontalMarginDp;
    }

    public int getVerticalMarginDp() {
        return verticalMarginDp;
    }

    public int getCornerRadiusDp() {
        return cornerRadiusDp;
    }

    public int getCoverSizeDp() {
        return coverSizeDp;
    }

    public int getCoverRadiusDp() {
        return coverRadiusDp;
    }

    public int getControlButtonWidthDp() {
        return controlButtonWidthDp;
    }

    public int getPlayButtonWidthDp() {
        return playButtonWidthDp;
    }

    public int getTopGapDp() {
        return topGapDp;
    }

    public DeterminateCircularProgressGeometry getProgressGeometry() {
        return progressGeometry;
    }
}
