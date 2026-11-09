package org.telegram.ui.Components;

public final class FilterChipGeometry {
    private final int heightDp;
    private final int unselectedCornerRadiusDp;
    private final int selectedCornerRadiusDp;
    private final int gapDp;
    private final float unselectedContainerAlpha;
    private final float selectedContainerAlpha;

    public FilterChipGeometry(
            int heightDp,
            int unselectedCornerRadiusDp,
            int selectedCornerRadiusDp,
            int gapDp,
            float unselectedContainerAlpha,
            float selectedContainerAlpha
    ) {
        if (heightDp <= 0
                || unselectedCornerRadiusDp < 0
                || unselectedCornerRadiusDp * 2 > heightDp
                || selectedCornerRadiusDp < unselectedCornerRadiusDp
                || selectedCornerRadiusDp * 2 > heightDp
                || gapDp < 0
                || unselectedContainerAlpha < 0.0f
                || unselectedContainerAlpha > 1.0f
                || selectedContainerAlpha < 0.0f
                || selectedContainerAlpha > 1.0f
                || selectedContainerAlpha < unselectedContainerAlpha) {
            throw new IllegalArgumentException();
        }

        this.heightDp = heightDp;
        this.unselectedCornerRadiusDp = unselectedCornerRadiusDp;
        this.selectedCornerRadiusDp = selectedCornerRadiusDp;
        this.gapDp = gapDp;
        this.unselectedContainerAlpha = unselectedContainerAlpha;
        this.selectedContainerAlpha = selectedContainerAlpha;
    }

    public int getHeightDp() {
        return heightDp;
    }

    public int getUnselectedCornerRadiusDp() {
        return unselectedCornerRadiusDp;
    }

    public int getSelectedCornerRadiusDp() {
        return selectedCornerRadiusDp;
    }

    public int getGapDp() {
        return gapDp;
    }

    public float getUnselectedContainerAlpha() {
        return unselectedContainerAlpha;
    }

    public float getSelectedContainerAlpha() {
        return selectedContainerAlpha;
    }
}
