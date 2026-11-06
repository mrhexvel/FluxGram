package org.telegram.ui.fluxgram;

import android.text.SpannableStringBuilder;
import android.text.Spanned;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.fluxgram.FolderSettings;
import org.telegram.ui.Components.ColoredImageSpan;

public final class FolderPresentation {
    public static CharSequence createTitle(
            CharSequence title,
            MessagesController.DialogFilter filter,
            FolderSettings.Policy policy
    ) {
        int mode = policy.getPresentationMode();
        if (mode == FolderSettings.MODE_TITLE) {
            return title;
        }

        SpannableStringBuilder result = new SpannableStringBuilder("i");
        int iconDrawable = resolveIcon(filter);
        ColoredImageSpan icon = new ColoredImageSpan(iconDrawable, ColoredImageSpan.ALIGN_CENTER);
        icon.setSize(AndroidUtilities.dp(FolderIconCatalog.containsDrawable(iconDrawable) ? 22 : 18));
        result.setSpan(icon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (mode == FolderSettings.MODE_TITLE_AND_ICON && title != null && title.length() > 0) {
            result.append("  ").append(title);
        }

        return result;
    }

    public static int resolveIcon(MessagesController.DialogFilter filter) {
        return filter == null
                ? R.drawable.filter_all
                : resolveIcon(filter.flags, filter.emoticon, filter.isDefault());
    }

    public static int resolveIcon(int flags, String emoticon, boolean defaultFilter) {
        if (defaultFilter) {
            return R.drawable.filter_all;
        }

        int selectedIcon = FolderIconCatalog.resolve(emoticon);
        if (selectedIcon != 0) {
            return selectedIcon;
        }

        if ((flags & MessagesController.DIALOG_FILTER_FLAG_ONLY_ARCHIVED) != 0) {
            return R.drawable.msg_folders_archive;
        }

        int included = flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        int privateChats = MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                | MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
        if (included == MessagesController.DIALOG_FILTER_FLAG_CONTACTS
                || included == MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS
                || included == privateChats) {
            return R.drawable.filter_private;
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_GROUPS) {
            return R.drawable.filter_group;
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_CHANNELS) {
            return R.drawable.filter_channels;
        }
        if (included == MessagesController.DIALOG_FILTER_FLAG_BOTS) {
            return R.drawable.filter_bots;
        }

        int excluded = flags & (
                MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
        );
        if (included == MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) {
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) {
                return R.drawable.filter_unmuted;
            }
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) {
                return R.drawable.filter_unread;
            }
            if (excluded == MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) {
                return R.drawable.msg_folders_archive;
            }
        }

        return R.drawable.filter_custom;
    }

    private FolderPresentation() {
    }
}
