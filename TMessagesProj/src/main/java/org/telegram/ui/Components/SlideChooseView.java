package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;

public class SlideChooseView extends View {

    private final SeekBarAccessibilityDelegate accessibilityDelegate;

    private Paint paint;
    private Paint linePaint;
    private TextPaint textPaint;
    private int lastDash;

    private int circleSize;
    private int gapSize;
    private int sideSide;
    private int lineSize;

    private int dashedFrom = -1;

    private boolean moving;
    private boolean startMoving;
    private float xTouchDown;
    private float yTouchDown;

    private int startMovingPreset;

    private String[] optionsStr;
    private int[] optionsSizes;
    private Drawable[] leftDrawables;

    private int selectedIndex;
    private int minIndex = Integer.MIN_VALUE;
    private float selectedIndexTouch;
    private AnimatedFloat selectedIndexAnimatedHolder = new AnimatedFloat(this, 120, CubicBezierInterpolator.DEFAULT);
    private AnimatedFloat movingAnimatedHolder = new AnimatedFloat(this, 150, CubicBezierInterpolator.DEFAULT);

    private Callback callback;
    private final Theme.ResourcesProvider resourcesProvider;

    private boolean touchWasClose = false;
    private DiscreteSliderGeometry discreteSliderGeometry;
    private final RectF materialRect = new RectF();
    private final RectF materialCornerRect = new RectF();
    private final Path materialPath = new Path();
    private final float[] materialRadii = new float[8];

    public SlideChooseView(Context context) {
        this(context, null);
    }

    public SlideChooseView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(AndroidUtilities.dp(2));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setTextSize(AndroidUtilities.dp(13));

        accessibilityDelegate = new IntSeekBarAccessibilityDelegate() {
            @Override
            protected int getProgress() {
                return selectedIndex;
            }

            @Override
            protected void setProgress(int progress) {
                setOption(progress);
            }

            @Override
            protected int getMaxValue() {
                return optionsStr.length - 1;
            }

            @Override
            protected CharSequence getContentDescription(View host) {
                return selectedIndex < optionsStr.length ? optionsStr[selectedIndex] : null;
            }
        };
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setOptions(int selected, String... options) {
        setOptions(selected, null, options);
    }

    public void setOptions(int selected, Drawable[] leftDrawables, String... options) {
        this.optionsStr = options;
        this.leftDrawables = leftDrawables;
        selectedIndex = selected;
        optionsSizes = new int[optionsStr.length];
        for (int i = 0; i < optionsStr.length; i++) {
            optionsSizes[i] = (int) Math.ceil(textPaint.measureText(optionsStr[i]));
        }
        if (this.leftDrawables != null) {
            for (Drawable drawable : this.leftDrawables) {
                drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
            }
        }
        requestLayout();
    }

    public void setMinAllowedIndex(int index) {
        if (index != -1 && optionsStr != null) {
            index = Math.min(index, optionsStr.length - 1);
        }
        if (minIndex != index) {
            minIndex = index;
            if (selectedIndex < index) {
                selectedIndex = index;
            }
            invalidate();
        }
    }

    public void setDashedFrom(int from) {
        dashedFrom = from;
    }

    public void setDiscreteSliderGeometry(DiscreteSliderGeometry geometry) {
        discreteSliderGeometry = geometry;
        requestLayout();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float indexTouch;
        if (usesDiscreteSliderGeometry()) {
            float range = getMeasuredWidth() - sideSide * 2.0f;
            float visualIndex = MathUtils.clamp(
                    (x - sideSide) / range * (optionsStr.length - 1),
                    0,
                    optionsStr.length - 1
            );
            indexTouch = LocaleController.isRTL
                    ? optionsStr.length - 1 - visualIndex
                    : visualIndex;
        } else {
            indexTouch = MathUtils.clamp((x - sideSide + circleSize / 2f) / (lineSize + gapSize * 2 + circleSize), 0, optionsStr.length - 1);
        }
        boolean isClose = Math.abs(indexTouch - Math.round(indexTouch)) < .35f;
        if (isClose) {
            indexTouch = Math.round(indexTouch);
        }
        if (minIndex != Integer.MIN_VALUE) {
            indexTouch = Math.max(indexTouch, minIndex);
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            xTouchDown = x;
            yTouchDown = y;
            selectedIndexTouch = indexTouch;
            startMovingPreset = selectedIndex;
            startMoving = true;
            invalidate();
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (!moving) {
                if (Math.abs(xTouchDown - x) > Math.abs(yTouchDown - y)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            if (startMoving) {
                if (Math.abs(xTouchDown - x) >= AndroidUtilities.touchSlop) {
                    moving = true;
                    startMoving = false;
                }
            }
            if (moving) {
                selectedIndexTouch = indexTouch;
                invalidate();
                if (Math.round(selectedIndexTouch) != selectedIndex && isClose) {
                    setOption(Math.round(selectedIndexTouch));
                }
            }
            invalidate();
        } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (!moving) {
                selectedIndexTouch = indexTouch;
                if (event.getAction() == MotionEvent.ACTION_UP && Math.round(selectedIndexTouch) != selectedIndex) {
                    setOption(Math.round(selectedIndexTouch));
                }
            } else {
                if (usesDiscreteSliderGeometry()) {
                    if (event.getAction() == MotionEvent.ACTION_UP
                            && Math.round(indexTouch) != selectedIndex) {
                        setOption(Math.round(indexTouch));
                    }
                } else if (selectedIndex != startMovingPreset) {
                    setOption(selectedIndex);
                }
            }
            if (callback != null) {
                callback.onTouchEnd();
            }
            startMoving = false;
            moving = false;
            invalidate();
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    private void setOption(int index) {
        if (selectedIndex != index) {
            AndroidUtilities.vibrateCursor(this);
        }
        selectedIndex = index;
        if (callback != null) {
            callback.onOptionSelected(index);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(74), MeasureSpec.EXACTLY));
        circleSize = AndroidUtilities.dp(6);
        gapSize = AndroidUtilities.dp(2);
        sideSide = AndroidUtilities.dp(22);
        lineSize = (getMeasuredWidth() - circleSize * optionsStr.length - gapSize * 2 * (optionsStr.length - 1) - sideSide * 2) / Math.max(1, optionsStr.length - 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (usesDiscreteSliderGeometry()) {
            drawDiscreteSlider(canvas);
            return;
        }

        float selectedIndexAnimated = selectedIndexAnimatedHolder.set(selectedIndex);
        float movingAnimated = movingAnimatedHolder.set(moving ? 1 : 0);
        int cy = getMeasuredHeight() / 2 + AndroidUtilities.dp(11);

        for (int a = 0; a < optionsStr.length; a++) {
            int cx = sideSide + (lineSize + gapSize * 2 + circleSize) * a + circleSize / 2;
            float t = Math.max(0, 1f - Math.abs(a - selectedIndexAnimated));
            float ut = MathUtils.clamp(selectedIndexAnimated - a + 1f, 0, 1);
            int color = ColorUtils.blendARGB(getThemedColor(Theme.key_switchTrack), Theme.multAlpha(getThemedColor(Theme.key_switchTrackChecked), minIndex != Integer.MIN_VALUE && a <= minIndex ? .50f : 1.0f), ut);
            paint.setColor(color);
            linePaint.setColor(color);
            canvas.drawCircle(cx, cy, AndroidUtilities.lerp(circleSize / 2, AndroidUtilities.dp(6), t), paint);
            if (a != 0) {
                int x = cx - circleSize / 2 - gapSize - lineSize;
                int width = lineSize;
                if (dashedFrom != -1 && a - 1 >= dashedFrom) {
                    x += AndroidUtilities.dp(3);
                    width -= AndroidUtilities.dp(3);
                    int dash = width / AndroidUtilities.dp(13);
                    if (lastDash != dash) {
                        float gap = (width - dash * AndroidUtilities.dp(8)) / (float) (dash - 1);
                        linePaint.setPathEffect(new DashPathEffect(new float[]{AndroidUtilities.dp(6), gap}, 0));
                        lastDash = dash;
                    }
                    canvas.drawLine(x + AndroidUtilities.dp(1), cy, x + width - AndroidUtilities.dp(1), cy, linePaint);
                } else {
                    float nt = MathUtils.clamp(1f - Math.abs(a - selectedIndexAnimated - 1), 0, 1);
                    float nct = MathUtils.clamp(1f - Math.min(Math.abs(a - selectedIndexAnimated), Math.abs(a - selectedIndexAnimated - 1)), 0, 1);
                    width -= AndroidUtilities.dp(3) * nct;
                    x += AndroidUtilities.dp(3) * nt;
                    canvas.drawRect(x, cy - AndroidUtilities.dp(1), x + width, cy + AndroidUtilities.dp(1), paint);
                }
            }
            int size = optionsSizes[a];
            String text = optionsStr[a];
            textPaint.setColor(ColorUtils.blendARGB(getThemedColor(Theme.key_windowBackgroundWhiteGrayText), getThemedColor(Theme.key_windowBackgroundWhiteBlueText), t));

            if (leftDrawables != null) {
                canvas.save();
                if (a == 0) {
                    canvas.translate(AndroidUtilities.dp(12), AndroidUtilities.dp(15.5f));
                } else if (a == optionsStr.length - 1) {
                    canvas.translate(getMeasuredWidth() - size - AndroidUtilities.dp(22) - AndroidUtilities.dp(10), AndroidUtilities.dp(28) - AndroidUtilities.dp(12.5f));
                } else {
                    canvas.translate(cx - size / 2 - AndroidUtilities.dp(10), AndroidUtilities.dp(28) - AndroidUtilities.dp(12.5f));
                }
                leftDrawables[a].setColorFilter(textPaint.getColor(), PorterDuff.Mode.MULTIPLY);
                leftDrawables[a].draw(canvas);
                canvas.restore();
                canvas.save();
                canvas.translate((leftDrawables[a].getIntrinsicWidth() / 2f) - AndroidUtilities.dp(a == 0 ? 3 : 2), 0);
            }

            if (a == 0) {
                canvas.drawText(text, AndroidUtilities.dp(22), AndroidUtilities.dp(28), textPaint);
            } else if (a == optionsStr.length - 1) {
                canvas.drawText(text, getMeasuredWidth() - size - AndroidUtilities.dp(22), AndroidUtilities.dp(28), textPaint);
            } else {
                canvas.drawText(text, cx - size / 2, AndroidUtilities.dp(28), textPaint);
            }

            if (leftDrawables != null) {
                canvas.restore();
            }
        }

        float cx = sideSide + (lineSize + gapSize * 2 + circleSize) * selectedIndexAnimated + circleSize / 2;
        paint.setColor(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_switchTrackChecked), 80));
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(12 * movingAnimated), paint);
        paint.setColor(getThemedColor(Theme.key_switchTrackChecked));
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(6), paint);
    }

    private boolean usesDiscreteSliderGeometry() {
        return discreteSliderGeometry != null && leftDrawables == null && optionsStr != null && optionsStr.length > 1;
    }

    private float materialCenterForIndex(float index) {
        float visualIndex = LocaleController.isRTL ? optionsStr.length - 1 - index : index;
        return sideSide + (getMeasuredWidth() - sideSide * 2.0f)
                * visualIndex / (optionsStr.length - 1);
    }

    private void drawDiscreteSlider(Canvas canvas) {
        SliderGeometry geometry = discreteSliderGeometry.getSliderGeometry();
        float selectedIndexAnimated = selectedIndexAnimatedHolder.set(selectedIndex);
        float movingAnimated = movingAnimatedHolder.set(moving ? 1 : 0);
        float centerY = getMeasuredHeight() / 2.0f + AndroidUtilities.dp(11);
        float centerX = materialCenterForIndex(selectedIndexAnimated);
        float trackHeight = AndroidUtilities.dpf2(geometry.getTrackHeightDp());
        float outerRadius = trackHeight / 2.0f;
        float innerRadius = AndroidUtilities.dpf2(geometry.getTrackInsideCornerRadiusDp());
        float thumbWidth = AndroidUtilities.dpf2(geometry.getThumbWidthDp());
        float pressedWidth = thumbWidth * geometry.getPressedThumbWidthRatio();
        thumbWidth = AndroidUtilities.lerp(thumbWidth, pressedWidth, movingAnimated);
        float thumbHeight = AndroidUtilities.dpf2(geometry.getThumbHeightDp());
        float gap = AndroidUtilities.dpf2(geometry.getThumbTrackGapDp())
                - (AndroidUtilities.dpf2(geometry.getThumbWidthDp()) - thumbWidth) / 2.0f;
        float startX = sideSide;
        float endX = getMeasuredWidth() - sideSide;
        float trackLeft = startX - outerRadius;
        float trackRight = endX + outerRadius;
        float trackTop = centerY - trackHeight / 2.0f;
        float trackBottom = centerY + trackHeight / 2.0f;
        int activeColor = getThemedColor(Theme.key_player_progress);
        int inactiveColor = getThemedColor(Theme.key_player_progressBackground);
        float minimumSegmentWidth = outerRadius - gap;

        if (LocaleController.isRTL) {
            materialRect.set(trackLeft, trackTop, centerX - gap, trackBottom);
            if (materialRect.width() > minimumSegmentWidth) {
                drawMaterialTrack(canvas, materialRect, inactiveColor, outerRadius, innerRadius);
            }
            materialRect.set(centerX + gap, trackTop, trackRight, trackBottom);
            if (materialRect.width() > minimumSegmentWidth) {
                drawMaterialTrack(canvas, materialRect, activeColor, innerRadius, outerRadius);
            }
        } else {
            materialRect.set(trackLeft, trackTop, centerX - gap, trackBottom);
            if (materialRect.width() > minimumSegmentWidth) {
                drawMaterialTrack(canvas, materialRect, activeColor, outerRadius, innerRadius);
            }
            materialRect.set(centerX + gap, trackTop, trackRight, trackBottom);
            if (materialRect.width() > minimumSegmentWidth) {
                drawMaterialTrack(canvas, materialRect, inactiveColor, innerRadius, outerRadius);
            }
        }

        float tickRadius = AndroidUtilities.dpf2(discreteSliderGeometry.getTickSizeDp()) / 2.0f;
        for (int index = 0; index < optionsStr.length; index++) {
            float tickX = materialCenterForIndex(index);
            if (Math.abs(tickX - centerX) > gap + thumbWidth / 2.0f) {
                paint.setColor(index <= selectedIndexAnimated ? inactiveColor : activeColor);
                canvas.drawCircle(tickX, centerY, tickRadius, paint);
            }

            float selected = Math.max(0, 1.0f - Math.abs(index - selectedIndexAnimated));
            textPaint.setColor(ColorUtils.blendARGB(
                    getThemedColor(Theme.key_windowBackgroundWhiteGrayText),
                    getThemedColor(Theme.key_windowBackgroundWhiteBlueText),
                    selected
            ));
            int textWidth = optionsSizes[index];
            int visualIndex = LocaleController.isRTL ? optionsStr.length - 1 - index : index;
            float textX;
            if (visualIndex == 0) {
                textX = sideSide;
            } else if (visualIndex == optionsStr.length - 1) {
                textX = getMeasuredWidth() - textWidth - sideSide;
            } else {
                textX = tickX - textWidth / 2.0f;
            }
            canvas.drawText(optionsStr[index], textX, AndroidUtilities.dp(25), textPaint);
        }

        paint.setColor(activeColor);
        materialRect.set(
                centerX - thumbWidth / 2.0f,
                centerY - thumbHeight / 2.0f,
                centerX + thumbWidth / 2.0f,
                centerY + thumbHeight / 2.0f
        );
        canvas.drawRoundRect(materialRect, thumbWidth / 2.0f, thumbWidth / 2.0f, paint);
    }

    private void drawMaterialTrack(
            Canvas canvas,
            RectF bounds,
            int color,
            float leftRadius,
            float rightRadius
    ) {
        if (bounds.width() <= 0) {
            return;
        }

        paint.setColor(color);
        materialRadii[0] = leftRadius;
        materialRadii[1] = leftRadius;
        materialRadii[2] = rightRadius;
        materialRadii[3] = rightRadius;
        materialRadii[4] = rightRadius;
        materialRadii[5] = rightRadius;
        materialRadii[6] = leftRadius;
        materialRadii[7] = leftRadius;
        materialPath.reset();
        if (bounds.width() >= leftRadius + rightRadius) {
            materialPath.addRoundRect(bounds, materialRadii, Path.Direction.CW);
            canvas.drawPath(materialPath, paint);
            return;
        }

        float minimumRadius = Math.min(leftRadius, rightRadius);
        float maximumRadius = Math.max(leftRadius, rightRadius);
        materialPath.addRoundRect(bounds, minimumRadius, minimumRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(materialPath);
        if (leftRadius > rightRadius) {
            materialCornerRect.set(
                    bounds.left,
                    bounds.top,
                    bounds.left + 2.0f * maximumRadius,
                    bounds.bottom
            );
        } else {
            materialCornerRect.set(
                    bounds.right - 2.0f * maximumRadius,
                    bounds.top,
                    bounds.right,
                    bounds.bottom
            );
        }
        canvas.drawRoundRect(
                materialCornerRect,
                maximumRadius,
                maximumRadius,
                paint
        );
        canvas.restore();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        accessibilityDelegate.onInitializeAccessibilityNodeInfoInternal(this, info);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        return super.performAccessibilityAction(action, arguments) || accessibilityDelegate.performAccessibilityActionInternal(this, action, arguments);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }


    public interface Callback {
        void onOptionSelected(int index);

        default void onTouchEnd() {

        }
    }
}
