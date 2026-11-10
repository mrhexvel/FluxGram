package org.telegram.messenger.fluxgram;

public enum AudioMiniPlayerPosition {
    BOTTOM("bottom"),
    TOP("top");

    private final String persistedValue;

    AudioMiniPlayerPosition(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String getPersistedValue() {
        return persistedValue;
    }

    public static AudioMiniPlayerPosition decode(String value) {
        for (AudioMiniPlayerPosition position : values()) {
            if (position.persistedValue.equals(value)) {
                return position;
            }
        }

        return BOTTOM;
    }
}
