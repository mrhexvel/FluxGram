package org.telegram.messenger.fluxgram;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

public final class AudioMiniPlayerSettings {
    private static final String POSITION_KEY = "fluxgram_audio_mini_player_position";

    private AudioMiniPlayerSettings() {
    }

    public static AudioMiniPlayerPosition getPosition() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();

        try {
            return AudioMiniPlayerPosition.decode(preferences.getString(POSITION_KEY, null));
        } catch (ClassCastException exception) {
            return AudioMiniPlayerPosition.BOTTOM;
        }
    }

    public static boolean setPosition(AudioMiniPlayerPosition position) {
        if (position == null) {
            throw new IllegalArgumentException();
        }

        return MessagesController.getGlobalMainSettings()
                .edit()
                .putString(POSITION_KEY, position.getPersistedValue())
                .commit();
    }
}
