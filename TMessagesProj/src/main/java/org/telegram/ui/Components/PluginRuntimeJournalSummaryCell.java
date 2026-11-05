package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.plugins.Plugin;
import org.telegram.ui.ActionBar.Theme;

public final class PluginRuntimeJournalSummaryCell extends FrameLayout {
    private final Theme.ResourcesProvider resourcesProvider;
    private final FrameLayout iconContainer;
    private final TextView titleView;
    private final TextView statusView;
    private final TextView detailsView;

    public PluginRuntimeJournalSummaryCell(
            Context context,
            Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        iconContainer = new FrameLayout(context);
        addView(iconContainer, LayoutHelper.createFrame(
                48,
                48,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 16,
                0,
                LocaleController.isRTL ? 16 : 0,
                0));

        titleView = textView(context, 16, Theme.key_windowBackgroundWhiteBlackText, true);
        addView(titleView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                22,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT),
                LocaleController.isRTL ? 16 : 76,
                11,
                LocaleController.isRTL ? 76 : 16,
                0));

        statusView = textView(context, 14, Theme.key_windowBackgroundWhiteGrayText, false);
        addView(statusView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                20,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT),
                LocaleController.isRTL ? 16 : 76,
                35,
                LocaleController.isRTL ? 76 : 16,
                0));

        detailsView = textView(context, 13, Theme.key_windowBackgroundWhiteGrayText2, false);
        addView(detailsView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                19,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT),
                LocaleController.isRTL ? 16 : 76,
                57,
                LocaleController.isRTL ? 76 : 16,
                0));
    }

    public void setSummary(
            Plugin plugin,
            String pluginId,
            CharSequence name,
            CharSequence status,
            CharSequence details,
            int statusColor) {
        iconContainer.removeAllViews();
        FluxGramPluginIconView iconView = new FluxGramPluginIconView(
                getContext(), resourcesProvider, pluginId, name.toString());

        if (plugin != null) {
            iconView.load(plugin, resourcesProvider);
        }

        iconContainer.addView(iconView, LayoutHelper.createFrame(48, 48));
        titleView.setText(name);
        statusView.setText(status);
        statusView.setTextColor(statusColor);
        detailsView.setText(details);
        setContentDescription(name + ". " + status + ". " + details);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(84), MeasureSpec.EXACTLY));
    }

    private TextView textView(Context context, int size, int colorKey, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(colorKey, resourcesProvider));
        view.setTypeface(bold ? AndroidUtilities.bold() : AndroidUtilities.regular());
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    public static final class Factory extends UItem.UItemFactory<PluginRuntimeJournalSummaryCell> {
        static {
            setup(new Factory());
        }

        @Override
        public PluginRuntimeJournalSummaryCell createView(
                Context context,
                RecyclerListView listView,
                int currentAccount,
                int classGuid,
                Theme.ResourcesProvider resourcesProvider) {
            return new PluginRuntimeJournalSummaryCell(context, resourcesProvider);
        }

        @Override
        public void bindView(
                View view,
                UItem item,
                boolean divider,
                UniversalAdapter adapter,
                UniversalRecyclerView listView) {
            ((PluginRuntimeJournalSummaryCell) view).setSummary(
                    (Plugin) item.object,
                    (String) item.object2,
                    item.text,
                    item.subtext,
                    item.textValue,
                    item.intValue);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return TextUtils.equals((String) first.object2, (String) second.object2);
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return equals(first, second)
                    && first.object == second.object
                    && first.intValue == second.intValue
                    && TextUtils.equals(first.text, second.text)
                    && TextUtils.equals(first.subtext, second.subtext)
                    && TextUtils.equals(first.textValue, second.textValue);
        }

        public static UItem of(
                Plugin plugin,
                String pluginId,
                CharSequence name,
                CharSequence status,
                CharSequence details,
                int statusColor) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = plugin;
            item.object2 = pluginId;
            item.text = name;
            item.subtext = status;
            item.textValue = details;
            item.intValue = statusColor;
            return item;
        }
    }
}
