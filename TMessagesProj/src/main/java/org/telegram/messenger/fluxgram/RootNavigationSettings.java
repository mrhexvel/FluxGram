package org.telegram.messenger.fluxgram;

import android.content.SharedPreferences;

import org.telegram.messenger.MessagesController;

import java.util.Collection;

public final class RootNavigationSettings {
    private static final String STATE_KEY = "fluxgram_root_navigation_topology";

    private RootNavigationSettings() {
    }

    public static boolean hasExplicitState() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!preferences.contains(STATE_KEY)) {
            return false;
        }

        try {
            return RootNavigationPolicy.decodeExplicit(
                    preferences.getString(STATE_KEY, null)
            ) != null;
        } catch (ClassCastException exception) {
            return false;
        }
    }

    public static RootNavigationPolicy.State getState(boolean callsTabVisible) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (!preferences.contains(STATE_KEY)) {
            return RootNavigationPolicy.legacyDefault(callsTabVisible);
        }

        try {
            return RootNavigationPolicy.decode(
                    preferences.getString(STATE_KEY, null),
                    callsTabVisible
            );
        } catch (ClassCastException exception) {
            return RootNavigationPolicy.legacyDefault(callsTabVisible);
        }
    }

    public static boolean setOrder(Collection<String> orderedDestinations) {
        RootNavigationPolicy.State state = RootNavigationPolicy.normalize(orderedDestinations);

        return MessagesController.getGlobalMainSettings()
                .edit()
                .putString(STATE_KEY, RootNavigationPolicy.encode(state))
                .commit();
    }

    public static boolean reset() {
        return MessagesController.getGlobalMainSettings()
                .edit()
                .remove(STATE_KEY)
                .commit();
    }
}
