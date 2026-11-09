package org.telegram.ui.Components;

public final class ActiveIndicatorGeometry {
    private final int widthDp;
    private final int heightDp;
    private final int cornerRadiusDp;
    private final int horizontalMarginDp;

    public ActiveIndicatorGeometry(
            int widthDp,
            int heightDp,
            int cornerRadiusDp,
            int horizontalMarginDp
    ) {
        if (widthDp <= 0
                || heightDp <= 0
                || cornerRadiusDp < 0
                || cornerRadiusDp * 2 > Math.min(widthDp, heightDp)
                || horizontalMarginDp < 0
                || horizontalMarginDp * 2 >= widthDp) {
            throw new IllegalArgumentException();
        }

        this.widthDp = widthDp;
        this.heightDp = heightDp;
        this.cornerRadiusDp = cornerRadiusDp;
        this.horizontalMarginDp = horizontalMarginDp;
    }

    public int getWidthDp() {
        return widthDp;
    }

    public int getHeightDp() {
        return heightDp;
    }

    public int getCornerRadiusDp() {
        return cornerRadiusDp;
    }

    public int getHorizontalMarginDp() {
        return horizontalMarginDp;
    }
}
