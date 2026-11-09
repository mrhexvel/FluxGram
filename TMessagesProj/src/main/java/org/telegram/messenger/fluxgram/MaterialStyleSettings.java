package org.telegram.messenger.fluxgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

public final class MaterialStyleSettings {
    private static final String STATE = "fluxgram_material_style_state";

    private MaterialStyleSettings() {
    }

    public static MaterialStylePolicy.State getState() {
        return readState(MessagesController.getGlobalMainSettings());
    }

    public static MaterialStylePolicy.State getState(Context context) {
        return readState(context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE));
    }

    private static MaterialStylePolicy.State readState(SharedPreferences preferences) {
        String encoded;

        try {
            encoded = preferences.getString(STATE, null);
        } catch (ClassCastException exception) {
            encoded = null;
        }

        return MaterialStylePolicy.decode(encoded);
    }

    public static boolean setState(MaterialStylePolicy.State state) {
        return MessagesController.getGlobalMainSettings()
                .edit()
                .putString(STATE, MaterialStylePolicy.encode(state))
                .commit();
    }

    public static boolean reset() {
        return MessagesController.getGlobalMainSettings()
                .edit()
                .remove(STATE)
                .commit();
    }
}
