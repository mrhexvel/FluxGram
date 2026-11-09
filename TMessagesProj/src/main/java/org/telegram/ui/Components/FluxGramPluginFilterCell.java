package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.fluxgram.PluginListFilter;
import org.telegram.ui.ActionBar.Theme;

import java.util.EnumMap;

public final class FluxGramPluginFilterCell extends HorizontalScrollView {
    public interface Listener {
        void onFilterSelected(PluginListFilter.Filter filter);
    }

    private final LinearLayout content;
    private final EnumMap<PluginListFilter.Filter, FilterView> views =
            new EnumMap<>(PluginListFilter.Filter.class);
    private PluginListFilter.Filter selectedFilter = PluginListFilter.Filter.ALL;

    public FluxGramPluginFilterCell(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            FilterChipGeometry geometry,
            CharSequence[] labels,
            Listener listener
    ) {
        super(context);

        setClipToPadding(false);
        setFillViewport(false);
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setPadding(dp(16), 0, dp(16), 0);

        content = new LinearLayout(context);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setOrientation(LinearLayout.HORIZONTAL);
        addView(content, LayoutHelper.createScroll(
                LayoutHelper.WRAP_CONTENT,
                48,
                Gravity.START
        ));

        PluginListFilter.Filter[] filters = PluginListFilter.Filter.values();

        for (int index = 0; index < filters.length; index++) {
            PluginListFilter.Filter filter = filters[index];
            FilterView view = new FilterView(
                    context,
                    resourcesProvider,
                    geometry,
                    labels[index]
            );
            view.setOnClickListener(clicked -> {
                if (selectedFilter == filter) {
                    return;
                }

                setSelectedFilter(filter, true);
                listener.onFilterSelected(filter);
            });

            LinearLayout.LayoutParams params = LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT,
                    48
            );
            if (index > 0) {
                params.setMarginStart(dp(geometry.getGapDp()));
            }

            views.put(filter, view);
            content.addView(view, params);
        }

        setSelectedFilter(selectedFilter, false);
    }

    public void setSelectedFilter(PluginListFilter.Filter filter, boolean animated) {
        selectedFilter = filter == null ? PluginListFilter.Filter.ALL : filter;

        for (PluginListFilter.Filter value : PluginListFilter.Filter.values()) {
            FilterView view = views.get(value);
            if (view != null) {
                view.setChecked(value == selectedFilter, animated);
            }
        }
    }

    private static final class FilterView extends View {
        private final Theme.ResourcesProvider resourcesProvider;
        private final FilterChipGeometry geometry;
        private final CharSequence label;
        private final Paint containerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private ValueAnimator animator;
        private float checkedProgress;
        private boolean checked;

        private FilterView(
                Context context,
                Theme.ResourcesProvider resourcesProvider,
                FilterChipGeometry geometry,
                CharSequence label
        ) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            this.geometry = geometry;
            this.label = label;

            textPaint.setTextSize(dp(14));
            textPaint.setTypeface(AndroidUtilities.bold());
            setClickable(true);
            setFocusable(true);
            setContentDescription(label);
        }

        private void setChecked(boolean checked, boolean animated) {
            if (this.checked == checked && animator == null) {
                return;
            }

            this.checked = checked;
            setSelected(checked);

            if (animator != null) {
                animator.cancel();
                animator = null;
            }

            float target = checked ? 1.0f : 0.0f;
            if (!animated) {
                checkedProgress = target;
                invalidate();
                return;
            }

            animator = ValueAnimator.ofFloat(checkedProgress, target);
            animator.setDuration(200);
            animator.addUpdateListener(value -> {
                checkedProgress = (float) value.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator == animation) {
                        animator = null;
                    }
                }
            });
            animator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = Math.max(
                    dp(48),
                    (int) Math.ceil(textPaint.measureText(label, 0, label.length())) + dp(32)
            );
            setMeasuredDimension(width, dp(48));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);

            int unselectedColor = Theme.multAlpha(
                    Theme.getColor(Theme.key_actionBarTabUnactiveText, resourcesProvider),
                    geometry.getUnselectedContainerAlpha()
            );
            int selectedColor = Theme.multAlpha(
                    Theme.getColor(Theme.key_actionBarTabActiveText, resourcesProvider),
                    geometry.getSelectedContainerAlpha()
            );
            int textColor = ColorUtils.blendARGB(
                    Theme.getColor(Theme.key_actionBarTabUnactiveText, resourcesProvider),
                    Theme.getColor(Theme.key_actionBarTabActiveText, resourcesProvider),
                    checkedProgress
            );
            float radius = lerp(
                    geometry.getUnselectedCornerRadiusDp(),
                    geometry.getSelectedCornerRadiusDp(),
                    checkedProgress
            );
            float top = (getMeasuredHeight() - dp(geometry.getHeightDp())) / 2.0f;

            containerPaint.setColor(ColorUtils.blendARGB(
                    unselectedColor,
                    selectedColor,
                    checkedProgress
            ));
            rect.set(0, top, getMeasuredWidth(), top + dp(geometry.getHeightDp()));
            canvas.drawRoundRect(rect, dp(radius), dp(radius), containerPaint);

            if (isPressed()) {
                containerPaint.setColor(Theme.multAlpha(textColor, 0.10f));
                canvas.drawRoundRect(rect, dp(radius), dp(radius), containerPaint);
            }

            textPaint.setColor(textColor);
            Paint.FontMetricsInt metrics = textPaint.getFontMetricsInt();
            float baseline = getMeasuredHeight() / 2.0f
                    - (metrics.ascent + metrics.descent) / 2.0f;
            canvas.drawText(
                    label,
                    0,
                    label.length(),
                    (getMeasuredWidth() - textPaint.measureText(label, 0, label.length())) / 2.0f,
                    baseline,
                    textPaint
            );
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            checkedProgress = checked ? 1.0f : 0.0f;
            super.onDetachedFromWindow();
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }
}
