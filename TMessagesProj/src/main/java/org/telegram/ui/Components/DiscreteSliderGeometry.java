package org.telegram.ui.Components;

public final class DiscreteSliderGeometry {
    private final SliderGeometry sliderGeometry;
    private final float tickSizeDp;

    public DiscreteSliderGeometry(SliderGeometry sliderGeometry, float tickSizeDp) {
        if (sliderGeometry == null
                || tickSizeDp <= 0
                || tickSizeDp > sliderGeometry.getTrackHeightDp()) {
            throw new IllegalArgumentException();
        }

        this.sliderGeometry = sliderGeometry;
        this.tickSizeDp = tickSizeDp;
    }

    public SliderGeometry getSliderGeometry() {
        return sliderGeometry;
    }

    public float getTickSizeDp() {
        return tickSizeDp;
    }
}
