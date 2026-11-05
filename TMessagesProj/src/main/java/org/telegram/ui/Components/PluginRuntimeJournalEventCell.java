package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalExport;
import org.telegram.ui.ActionBar.Theme;

public final class PluginRuntimeJournalEventCell extends FrameLayout {
    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView detailView;
    private boolean topLine;
    private boolean bottomLine;

    public PluginRuntimeJournalEventCell(
            Context context,
            Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setPadding(
                AndroidUtilities.dp(7),
                AndroidUtilities.dp(7),
                AndroidUtilities.dp(7),
                AndroidUtilities.dp(7));
        addView(iconView, LayoutHelper.createFrame(
                32,
                32,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL,
                LocaleController.isRTL ? 0 : 16,
                0,
                LocaleController.isRTL ? 16 : 0,
                0));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(titleView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                24,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT),
                LocaleController.isRTL ? 16 : 64,
                12,
                LocaleController.isRTL ? 64 : 16,
                0));

        detailView = new TextView(context);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        detailView.setTypeface(AndroidUtilities.regular());
        detailView.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText2));
        detailView.setSingleLine(true);
        detailView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detailView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        detailView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(detailView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                22,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT),
                LocaleController.isRTL ? 16 : 64,
                38,
                LocaleController.isRTL ? 64 : 16,
                0));
    }

    public void setEvent(
            CharSequence title,
            CharSequence detail,
            int icon,
            int accent,
            boolean topLine,
            boolean bottomLine) {
        this.topLine = topLine;
        this.bottomLine = bottomLine;
        titleView.setText(title);
        detailView.setText(detail);
        iconView.setImageResource(icon);
        iconView.setColorFilter(accent);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Theme.multAlpha(accent, 0.12f));
        iconView.setBackground(background);
        linePaint.setColor(Theme.multAlpha(accent, 0.28f));
        linePaint.setStrokeWidth(AndroidUtilities.dp(2));
        setContentDescription(title + "\n" + detail);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = LocaleController.isRTL
                ? getWidth() - AndroidUtilities.dp(32)
                : AndroidUtilities.dp(32);

        if (topLine) {
            canvas.drawLine(x, 0, x, AndroidUtilities.dp(20), linePaint);
        }
        if (bottomLine) {
            canvas.drawLine(
                    x,
                    AndroidUtilities.dp(52),
                    x,
                    getHeight(),
                    linePaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public static final class Factory extends UItem.UItemFactory<PluginRuntimeJournalEventCell> {
        private static final int FLAG_TOP_LINE = 1;
        private static final int FLAG_BOTTOM_LINE = 1 << 1;

        static {
            setup(new Factory());
        }

        @Override
        public PluginRuntimeJournalEventCell createView(
                Context context,
                RecyclerListView listView,
                int currentAccount,
                int classGuid,
                Theme.ResourcesProvider resourcesProvider) {
            return new PluginRuntimeJournalEventCell(context, resourcesProvider);
        }

        @Override
        public void bindView(
                View view,
                UItem item,
                boolean divider,
                UniversalAdapter adapter,
                UniversalRecyclerView listView) {
            ((PluginRuntimeJournalEventCell) view).setEvent(
                    item.text,
                    item.subtext,
                    item.iconResId,
                    item.intValue,
                    (item.flags & FLAG_TOP_LINE) != 0,
                    (item.flags & FLAG_BOTTOM_LINE) != 0);
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.longValue == second.longValue;
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return first.longValue == second.longValue
                    && first.intValue == second.intValue
                    && first.iconResId == second.iconResId
                    && first.flags == second.flags
                    && android.text.TextUtils.equals(first.text, second.text)
                    && android.text.TextUtils.equals(first.subtext, second.subtext);
        }

        public static UItem of(
                PluginRuntimeJournalExport.Entry entry,
                CharSequence title,
                CharSequence detail,
                int icon,
                int accent,
                boolean topLine,
                boolean bottomLine) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = entry;
            item.longValue = entry.sequence;
            item.text = title;
            item.subtext = detail;
            item.iconResId = icon;
            item.intValue = accent;
            item.flags = (topLine ? FLAG_TOP_LINE : 0)
                    | (bottomLine ? FLAG_BOTTOM_LINE : 0);
            return item;
        }
    }
}
