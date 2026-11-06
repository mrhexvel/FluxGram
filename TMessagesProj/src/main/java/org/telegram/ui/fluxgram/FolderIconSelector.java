package org.telegram.ui.fluxgram;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

public final class FolderIconSelector {
    public interface Listener {
        void onSelected(String emoticon);
    }

    public static void show(BaseFragment fragment, View anchor, String current, Listener listener) {
        Context context = fragment.getContext();
        if (context == null || !(anchor.getParent() instanceof View)) {
            return;
        }

        View scrimView = (View) anchor.getParent();
        ItemOptions options = ItemOptions.makeOptions(fragment, scrimView)
                .setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)
                .translate(AndroidUtilities.dp(LocaleController.isRTL ? 13 : -13), 0);
        if (scrimView.getParent() instanceof RecyclerListView) {
            options.setScrimViewBackground(((RecyclerListView) scrimView.getParent()).getClipBackground(scrimView));
        }

        View fragmentView = fragment.getFragmentView();
        int availableWidth = fragmentView != null && fragmentView.getWidth() > 0
                ? fragmentView.getWidth()
                : anchor.getRootView().getWidth();
        int columns = 6;
        while (columns > 1 && AndroidUtilities.dp(columns * 50 + 40) > availableWidth) {
            columns--;
        }

        String normalizedCurrent = FolderIconCatalog.normalize(current);

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(columns);
        for (int index = 0; index < FolderIconCatalog.size(); index++) {
            String emoticon = FolderIconCatalog.getEmoticon(index);
            boolean selected = TextUtils.equals(normalizedCurrent, emoticon);

            FolderIconView icon = new FolderIconView(context, fragment.getResourceProvider(), selected);
            icon.setScaleType(ImageView.ScaleType.FIT_XY);
            icon.setPadding(
                    AndroidUtilities.dp(6),
                    AndroidUtilities.dp(6),
                    AndroidUtilities.dp(6),
                    AndroidUtilities.dp(6)
            );
            icon.setImageResource(FolderIconCatalog.getDrawable(index));
            icon.setSelected(selected);
            icon.setContentDescription(LocaleController.formatString(
                    org.telegram.messenger.R.string.FluxGramFolderIconValue,
                    emoticon
            ));
            icon.setOnClickListener(view -> {
                options.dismiss();
                if (!selected) {
                    listener.onSelected(emoticon);
                }
            });
            grid.addView(icon, LayoutHelper.createFrame(48, 48, Gravity.CENTER, 1, 1, 1, 1));
        }

        options.addView(grid, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT,
                4,
                4,
                4,
                4
        ));
        options.show();
    }

    private static class FolderIconView extends ImageView {
        private final Theme.ResourcesProvider resourcesProvider;
        private final boolean selectedIcon;
        private int lastIconColor;
        private int lastSelectorColor;

        private FolderIconView(Context context, Theme.ResourcesProvider resourcesProvider, boolean selectedIcon) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            this.selectedIcon = selectedIcon;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int selectedColor = Theme.getColor(Theme.key_windowBackgroundWhiteValueText, resourcesProvider);
            int iconColor = selectedIcon
                    ? selectedColor
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider);
            int selectorColor = Theme.getColor(Theme.key_listSelector, resourcesProvider);
            if (lastIconColor != iconColor || lastSelectorColor != selectorColor) {
                lastIconColor = iconColor;
                lastSelectorColor = selectorColor;
                setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
                setBackground(Theme.createRadSelectorDrawable(
                        selectedIcon ? Theme.multAlpha(selectedColor, 0.16f) : 0,
                        selectorColor,
                        7,
                        7
                ));
            }
            super.onDraw(canvas);
        }
    }

    private FolderIconSelector() {
    }
}
