package android.graphics.drawable;

import android.graphics.Canvas;

public abstract class Drawable {
    private boolean autoMirrored;

    public int getIntrinsicWidth() {
        return 24;
    }

    public int getIntrinsicHeight() {
        return 24;
    }

    public void setBounds(int left, int top, int right, int bottom) {
    }

    public void draw(Canvas canvas) {
    }

    public void setAutoMirrored(boolean mirrored) {
        autoMirrored = mirrored;
    }

    public boolean isAutoMirrored() {
        return autoMirrored;
    }
}
