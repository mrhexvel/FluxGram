package org.telegram.ui.fluxgram;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

public final class FolderIconCatalog {
    public static final String DEFAULT_EMOTICON = "\uD83D\uDCC1";

    private static final String[] EMOTICONS = {
            "\uD83D\uDC31", "\uD83D\uDCD5", "\uD83D\uDCB0", "\uD83C\uDFAE", "\uD83D\uDCA1",
            "\uD83D\uDC4C", "\uD83C\uDFB5", "\uD83C\uDFA8", "\u2708", "\u26BD",
            "\u2B50", "\uD83C\uDF93", "\uD83D\uDEEB", "\uD83D\uDC64", "\uD83D\uDC65",
            "\uD83D\uDCAC", "\u2705", "\uD83E\uDD16", "\uD83D\uDC51", "\uD83C\uDF39",
            "\uD83C\uDFE0", "\u2764", "\uD83C\uDFAD", "\uD83C\uDF78", "\uD83D\uDCC8",
            "\uD83D\uDCBC", "\uD83D\uDD14", "\uD83D\uDCE2", DEFAULT_EMOTICON, "\uD83D\uDCCB"
    };

    private static final int[] DRAWABLES = {
            R.drawable.filter_cat, R.drawable.filter_book, R.drawable.filter_money,
            R.drawable.filter_game, R.drawable.filter_light, R.drawable.filter_like,
            R.drawable.filter_note, R.drawable.filter_palette, R.drawable.filter_travel,
            R.drawable.filter_sport, R.drawable.filter_favorite, R.drawable.filter_study,
            R.drawable.filter_airplane, R.drawable.filter_private, R.drawable.filter_group,
            R.drawable.filter_all, R.drawable.filter_unread, R.drawable.filter_bots,
            R.drawable.filter_crown, R.drawable.filter_flower, R.drawable.filter_home,
            R.drawable.filter_love, R.drawable.filter_mask, R.drawable.filter_party,
            R.drawable.filter_trade, R.drawable.filter_work, R.drawable.filter_unmuted,
            R.drawable.filter_channels, R.drawable.filter_custom, R.drawable.filter_setup
    };

    public static int size() {
        return EMOTICONS.length;
    }

    public static String getEmoticon(int index) {
        return EMOTICONS[index];
    }

    public static int getDrawable(int index) {
        return DRAWABLES[index];
    }

    public static int resolve(String emoticon) {
        String normalized = normalize(emoticon);
        if (normalized == null || normalized.isEmpty()) {
            return 0;
        }

        for (int index = 0; index < EMOTICONS.length; index++) {
            if (EMOTICONS[index].equals(normalized)) {
                return DRAWABLES[index];
            }
        }

        return 0;
    }

    public static boolean containsDrawable(int drawable) {
        for (int value : DRAWABLES) {
            if (value == drawable) {
                return true;
            }
        }

        return false;
    }

    public static String resolveForFlags(int flags) {
        int included = flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        if (included == MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) {
            int excluded = flags & (
                    MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED
                            | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ
                            | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
            );
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) {
                return "\u2705";
            }
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) {
                return "\uD83D\uDD14";
            }
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) {
                return null;
            }
        }

        int privateChats = MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
        if (included == MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                || included == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
                || included == privateChats) {
            return "\uD83D\uDC64";
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_GROUPS) {
            return "\uD83D\uDC65";
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_BOTS) {
            return "\uD83E\uDD16";
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_CHANNELS) {
            return "\uD83D\uDCE2";
        }

        return DEFAULT_EMOTICON;
    }

    public static String normalize(String emoticon) {
        return emoticon == null ? null : emoticon.replace("\uFE0F", "");
    }

    private FolderIconCatalog() {
    }
}
