package org.telegram.ui.Components;

public final class SwitchGeometry {
    private final int trackWidthDp;
    private final int trackHeightDp;
    private final int uncheckedThumbDiameterDp;
    private final int checkedThumbDiameterDp;
    private final int morphThumbWidthDp;
    private final int morphThumbHeightDp;
    private final int trackOutlineWidthDp;
    private final int cellOuterMarginDp;
    private final int cellContentGapDp;

    public SwitchGeometry(
            int trackWidthDp,
            int trackHeightDp,
            int uncheckedThumbDiameterDp,
            int checkedThumbDiameterDp,
            int morphThumbWidthDp,
            int morphThumbHeightDp,
            int trackOutlineWidthDp,
            int cellOuterMarginDp,
            int cellContentGapDp
    ) {
        if (trackWidthDp < trackHeightDp
                || trackHeightDp <= 0
                || uncheckedThumbDiameterDp <= 0
                || checkedThumbDiameterDp <= 0
                || uncheckedThumbDiameterDp > trackHeightDp
                || checkedThumbDiameterDp > trackHeightDp
                || morphThumbWidthDp <= 0
                || morphThumbWidthDp > trackWidthDp
                || morphThumbHeightDp <= 0
                || morphThumbHeightDp > trackHeightDp
                || trackOutlineWidthDp < 0
                || cellOuterMarginDp < 0
                || cellContentGapDp < 0) {
            throw new IllegalArgumentException();
        }

        this.trackWidthDp = trackWidthDp;
        this.trackHeightDp = trackHeightDp;
        this.uncheckedThumbDiameterDp = uncheckedThumbDiameterDp;
        this.checkedThumbDiameterDp = checkedThumbDiameterDp;
        this.morphThumbWidthDp = morphThumbWidthDp;
        this.morphThumbHeightDp = morphThumbHeightDp;
        this.trackOutlineWidthDp = trackOutlineWidthDp;
        this.cellOuterMarginDp = cellOuterMarginDp;
        this.cellContentGapDp = cellContentGapDp;
    }

    public int getTrackWidthDp() {
        return trackWidthDp;
    }

    public int getTrackHeightDp() {
        return trackHeightDp;
    }

    public int getCellOuterMarginDp() {
        return cellOuterMarginDp;
    }

    public int getTrackOutlineWidthDp() {
        return trackOutlineWidthDp;
    }

    public int getUncheckedThumbDiameterDp() {
        return uncheckedThumbDiameterDp;
    }

    public int getCheckedThumbDiameterDp() {
        return checkedThumbDiameterDp;
    }

    public int getMorphThumbWidthDp() {
        return morphThumbWidthDp;
    }

    public int getMorphThumbHeightDp() {
        return morphThumbHeightDp;
    }

    public int getCellEndInsetDp() {
        return trackWidthDp + cellOuterMarginDp + cellContentGapDp;
    }

    public void resolve(
            float measuredWidth,
            float measuredHeight,
            float density,
            float positionProgress,
            float thumbWidthDp,
            float thumbHeightDp,
            Resolved output
    ) {
        if (!Float.isFinite(measuredWidth)
                || !Float.isFinite(measuredHeight)
                || !Float.isFinite(density)
                || !Float.isFinite(positionProgress)
                || !Float.isFinite(thumbWidthDp)
                || !Float.isFinite(thumbHeightDp)
                || measuredWidth < 0
                || measuredHeight < 0
                || density <= 0
                || thumbWidthDp <= 0
                || thumbHeightDp <= 0
                || thumbWidthDp > trackWidthDp
                || thumbHeightDp > trackHeightDp
                || output == null) {
            throw new IllegalArgumentException();
        }

        float targetHeight = trackHeightDp * density;
        float targetWidth = trackWidthDp * density;
        float scale = Math.min(
                1.0f,
                Math.min(measuredHeight / targetHeight, measuredWidth / targetWidth)
        );
        float boundedPositionProgress = Math.max(0.0f, Math.min(1.0f, positionProgress));
        float height = targetHeight * scale;
        float width = targetWidth * scale;
        float x = (measuredWidth - width) / 2.0f;
        float y = (measuredHeight - height) / 2.0f;
        float thumbCenterX = x + height / 2.0f + (width - height) * boundedPositionProgress;

        output.set(
                x,
                y,
                width,
                height,
                thumbCenterX,
                measuredHeight / 2.0f,
                thumbWidthDp * density * scale,
                thumbHeightDp * density * scale
        );
    }

    public static final class Resolved {
        private float x;
        private float y;
        private float width;
        private float height;
        private float thumbCenterX;
        private float thumbCenterY;
        private float thumbWidth;
        private float thumbHeight;
        private float thumbRadius;

        public Resolved() {
        }

        private void set(
                float x,
                float y,
                float width,
                float height,
                float thumbCenterX,
                float thumbCenterY,
                float thumbWidth,
                float thumbHeight
        ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.thumbCenterX = thumbCenterX;
            this.thumbCenterY = thumbCenterY;
            this.thumbWidth = thumbWidth;
            this.thumbHeight = thumbHeight;
            this.thumbRadius = Math.min(thumbWidth, thumbHeight) / 2.0f;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }

        public float getThumbCenterX() {
            return thumbCenterX;
        }

        public float getThumbCenterY() {
            return thumbCenterY;
        }

        public float getThumbWidth() {
            return thumbWidth;
        }

        public float getThumbHeight() {
            return thumbHeight;
        }

        public float getThumbRadius() {
            return thumbRadius;
        }
    }
}
