/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Keep;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashSet;

public class RadialProgressView extends View implements Theme.Colorable {

    private static volatile CircularProgressGeometry defaultCircularProgressGeometry;
    private static boolean defaultCircularProgressGeometryInitialized;
    private static final HashSet<RadialProgressView> circularProgressViews = new HashSet<>();
    private static ContentObserver circularProgressAnimatorScaleObserver;
    private static ContentResolver circularProgressContentResolver;

    private long lastUpdateTime;
    private float radOffset;
    private float currentCircleLength;
    private boolean risingCircleLength;
    private float currentProgressTime;
    private RectF cicleRect = new RectF();
    private boolean useSelfAlpha;
    private float drawingCircleLenght;

    private int progressColor;

    private DecelerateInterpolator decelerateInterpolator;
    private AccelerateInterpolator accelerateInterpolator;
    private Paint progressPaint;
    private static final float rotationTime = 2000;
    private static final float risingTime = 500;
    private int size;

    private float currentProgress;
    private float progressAnimationStart;
    private int progressTime;
    private float animatedProgress;
    private boolean toCircle;
    private float toCircleProgress;

    private boolean noProgress = true;
    private final Theme.ResourcesProvider resourcesProvider;
    private final CircularProgressGeometry.Resolved circularProgressResolved =
            new CircularProgressGeometry.Resolved();
    private final Paint circularProgressTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circularProgressPath = new Path();
    private final Path circularProgressDisplayedPath = new Path();
    private final PathMeasure circularProgressPathMeasure = new PathMeasure();
    private CircularProgressGeometry circularProgressGeometry;
    private boolean circularProgressGeometryExplicit;
    private boolean circularProgressTrackColorExplicit;
    private double circularProgressTime;
    private long lastCircularProgressUpdateTime;
    private float circularProgressPathRadius;
    private float circularProgressAnimatorDurationScale = 1.0f;
    private Drawable circularProgressStaticDrawable;
    private float progressStrokeWidth;

    public RadialProgressView(Context context) {
        this(context, null);
    }

    public RadialProgressView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        circularProgressGeometry = defaultCircularProgressGeometry;

        size = AndroidUtilities.dp(40);

        progressColor = getThemedColor(Theme.key_progressCircle);
        decelerateInterpolator = new DecelerateInterpolator();
        accelerateInterpolator = new AccelerateInterpolator();
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressStrokeWidth = AndroidUtilities.dp(3);
        progressPaint.setStrokeWidth(progressStrokeWidth);
        progressPaint.setColor(progressColor);
        circularProgressTrackPaint.setStyle(Paint.Style.STROKE);
        circularProgressTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        updateCircularProgressTrackColor();
        themedProgressColor = progressColor;
    }

    public static synchronized void initializeDefaultCircularProgressGeometry(
            CircularProgressGeometry geometry
    ) {
        if (defaultCircularProgressGeometryInitialized) {
            return;
        }

        defaultCircularProgressGeometry = geometry;
        defaultCircularProgressGeometryInitialized = true;
    }

    // the key progressColor came from (Theme.key_progressCircle by default, see setProgressColorKey), and the color it gave
    private int progressColorKey = Theme.key_progressCircle;
    private int themedProgressColor;

    /** same as setProgressColor(getThemedColor(key)), but follows theme changes */
    public void setProgressColorKey(int key) {
        progressColorKey = key;
        setProgressColor(getThemedColor(key));
        themedProgressColor = progressColor;
    }

    @Override
    public void updateColors() {
        try {
            if (progressPaint == null || progressColor != themedProgressColor) {
                return; // custom color set by the caller
            }
            final int color = getThemedColor(progressColorKey);
            themedProgressColor = color;
            if (color != progressColor) {
                final int alpha = progressPaint.getAlpha();
                setProgressColor(color);
                themedProgressColor = color;
                if (useSelfAlpha) {
                    progressPaint.setAlpha(alpha);
                }
                invalidate();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        colors.add(progressColorKey);
    }

    public void setUseSelfAlpha(boolean value) {
        useSelfAlpha = value;
    }

    @Keep
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (useSelfAlpha) {
            Drawable background = getBackground();
            int a = (int) (alpha * 255);
            if (background != null) {
                background.setAlpha(a);
            }
            progressPaint.setAlpha(a);
        }
    }

    public void setNoProgress(boolean value) {
        noProgress = value;
        if (!value) {
            clearDefaultCircularProgressGeometry();
        }
    }

    public void setProgress(float value) {
        currentProgress = value;
        if (animatedProgress > value) {
            animatedProgress = value;
        }
        progressAnimationStart = animatedProgress;
        progressTime = 0;
    }

    public void sync(RadialProgressView from) {
        lastUpdateTime = from.lastUpdateTime;
        radOffset = from.radOffset;
        toCircle = from.toCircle;
        toCircleProgress = from.toCircleProgress;
        noProgress = from.noProgress;
        currentCircleLength = from.currentCircleLength;
        drawingCircleLenght = from.drawingCircleLenght;
        currentProgressTime = from.currentProgressTime;
        currentProgress = from.currentProgress;
        progressTime = from.progressTime;
        animatedProgress = from.animatedProgress;
        risingCircleLength = from.risingCircleLength;
        progressAnimationStart = from.progressAnimationStart;
        circularProgressTime = from.circularProgressTime;
        lastCircularProgressUpdateTime = from.lastCircularProgressUpdateTime;
        updateAnimation(17 * 5);
    }

    private void updateAnimation() {
        if (circularProgressGeometry != null && noProgress) {
            if (circularProgressAnimatorDurationScale == 0.0f) {
                return;
            }

            long newTime = SystemClock.uptimeMillis();

            if (lastCircularProgressUpdateTime != 0) {
                updateAnimation(Math.max(0, newTime - lastCircularProgressUpdateTime));
            } else {
                invalidate();
            }

            lastCircularProgressUpdateTime = newTime;

            return;
        }

        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        if (dt > 17) {
            dt = 17;
        }
        lastUpdateTime = newTime;
        updateAnimation(dt);
    }

    private void updateAnimation(long dt) {
        if (circularProgressGeometry != null && noProgress) {
            if (circularProgressAnimatorDurationScale == 0.0f) {
                return;
            }

            circularProgressTime = (circularProgressTime
                    + dt / circularProgressAnimatorDurationScale)
                    % circularProgressGeometry.getTotalDurationMs();
            invalidate();

            return;
        }

        radOffset += 360 * dt / rotationTime;
        int count = (int) (radOffset / 360);
        radOffset -= count * 360;

        if (toCircle && toCircleProgress != 1f) {
            toCircleProgress += 16 / 220f;
            if (toCircleProgress > 1f) {
                toCircleProgress = 1f;
            }
        } else if (!toCircle && toCircleProgress != 0f) {
            toCircleProgress -= 16 / 400f;
            if (toCircleProgress < 0) {
                toCircleProgress = 0f;
            }
        }

        if (noProgress) {
            if (toCircleProgress == 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= risingTime) {
                    currentProgressTime = risingTime;
                }
                if (risingCircleLength) {
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                } else {
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                }

                if (currentProgressTime == risingTime) {
                    if (risingCircleLength) {
                        radOffset += 270;
                        currentCircleLength = -266;
                    }
                    risingCircleLength = !risingCircleLength;
                    currentProgressTime = 0;
                }
            } else {
                if (risingCircleLength) {
                    float old = currentCircleLength;
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                    currentCircleLength += 360 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                } else {
                    float old = currentCircleLength;
                    currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                    currentCircleLength -= 364 * toCircleProgress;
                    float dx = old - currentCircleLength;
                    if (dx > 0) {
                        radOffset += old - currentCircleLength;
                    }
                }
            }
        } else {
            float progressDiff = currentProgress - progressAnimationStart;
            if (progressDiff > 0) {
                progressTime += dt;
                if (progressTime >= 200.0f) {
                    animatedProgress = progressAnimationStart = currentProgress;
                    progressTime = 0;
                } else {
                    animatedProgress = progressAnimationStart + progressDiff * AndroidUtilities.decelerateInterpolator.getInterpolation(progressTime / 200.0f);
                }
            }
            currentCircleLength = Math.max(4, 360 * animatedProgress);
        }
        invalidate();
    }

    public void setSize(int value) {
        size = value;
        if (value != AndroidUtilities.dp(40)) {
            clearDefaultCircularProgressGeometry();
        }
        invalidate();
    }

    public void setStrokeWidth(float value) {
        progressStrokeWidth = AndroidUtilities.dp(value);
        progressPaint.setStrokeWidth(progressStrokeWidth);
        clearDefaultCircularProgressGeometry();
    }

    public void setCircularProgressGeometry(CircularProgressGeometry geometry) {
        circularProgressGeometryExplicit = true;
        setCircularProgressGeometryInternal(geometry);
    }

    private void setCircularProgressGeometryInternal(CircularProgressGeometry geometry) {
        if (circularProgressGeometry == geometry) {
            return;
        }

        circularProgressGeometry = geometry;
        circularProgressTime = 0;
        lastCircularProgressUpdateTime = 0;
        circularProgressPathRadius = 0;

        if (circularProgressGeometry == null) {
            unregisterCircularProgressAnimatorScaleObserver();
        } else {
            updateCircularProgressAnimatorDurationScale();
            registerCircularProgressAnimatorScaleObserver();
        }

        invalidate();
    }

    public void setCircularProgressTrackColor(int color) {
        circularProgressTrackColorExplicit = true;
        circularProgressTrackPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateCircularProgressAnimatorDurationScale();
        registerCircularProgressAnimatorScaleObserver();
    }

    @Override
    protected void onDetachedFromWindow() {
        unregisterCircularProgressAnimatorScaleObserver();
        lastCircularProgressUpdateTime = 0;
        super.onDetachedFromWindow();
    }

    private void registerCircularProgressAnimatorScaleObserver() {
        if (circularProgressGeometry == null || !isAttachedToWindow()) {
            return;
        }

        if (!circularProgressViews.add(this)) {
            return;
        }

        if (circularProgressAnimatorScaleObserver == null) {
            circularProgressAnimatorScaleObserver = new ContentObserver(
                    new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    updateCircularProgressAnimatorDurationScales();
                }
            };
        }

        if (circularProgressContentResolver != null) {
            return;
        }

        circularProgressContentResolver = getContext().getApplicationContext().getContentResolver();
        circularProgressContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                circularProgressAnimatorScaleObserver
        );
    }

    private void unregisterCircularProgressAnimatorScaleObserver() {
        if (!circularProgressViews.remove(this) || !circularProgressViews.isEmpty()) {
            return;
        }

        if (circularProgressContentResolver != null) {
            circularProgressContentResolver.unregisterContentObserver(
                    circularProgressAnimatorScaleObserver);
            circularProgressContentResolver = null;
        }
    }

    private void updateCircularProgressAnimatorDurationScale() {
        if (circularProgressGeometry == null) {
            circularProgressAnimatorDurationScale = 1.0f;

            return;
        }

        circularProgressAnimatorDurationScale =
                readCircularProgressAnimatorDurationScale(getContext());
        lastCircularProgressUpdateTime = 0;
        invalidate();
    }

    private static void updateCircularProgressAnimatorDurationScales() {
        if (circularProgressViews.isEmpty()) {
            return;
        }

        RadialProgressView first = circularProgressViews.iterator().next();
        float durationScale = readCircularProgressAnimatorDurationScale(first.getContext());
        for (RadialProgressView view : circularProgressViews) {
            view.circularProgressAnimatorDurationScale = durationScale;
            view.lastCircularProgressUpdateTime = 0;
            view.invalidate();
        }
    }

    private static float readCircularProgressAnimatorDurationScale(Context context) {
        float durationScale = Settings.Global.getFloat(
                context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
        );

        if (Float.isNaN(durationScale) || Float.isInfinite(durationScale) || durationScale < 0.0f) {
            return 1.0f;
        }

        return durationScale;
    }

    public void setProgressColor(int color) {
        progressColor = color;
        progressPaint.setColor(progressColor);
        updateCircularProgressTrackColor();
    }

    public void toCircle(boolean toCircle, boolean animated) {
        clearDefaultCircularProgressGeometry();
        this.toCircle = toCircle;
        if (!animated) {
            toCircleProgress = toCircle ? 1f : 0f;
        }
    }

    private void clearDefaultCircularProgressGeometry() {
        if (!circularProgressGeometryExplicit
                && circularProgressGeometry == defaultCircularProgressGeometry) {
            setCircularProgressGeometryInternal(null);
        }
    }

    private void updateCircularProgressTrackColor() {
        if (!circularProgressTrackColorExplicit) {
            circularProgressTrackPaint.setColor(
                    (progressColor & 0x00ffffff) | 0x2e000000);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int drawingSize = getDrawingSize();
        int x = (getMeasuredWidth() - drawingSize) / 2;
        int y = (getMeasuredHeight() - drawingSize) / 2;
        drawProgress(canvas, x, y, x + drawingSize, y + drawingSize);
    }

    public void draw(Canvas canvas, float cx, float cy) {
        int drawingSize = getDrawingSize();
        drawProgress(
                canvas,
                cx - drawingSize / 2f,
                cy - drawingSize / 2f,
                cx + drawingSize / 2f,
                cy + drawingSize / 2f
        );
    }

    private int getDrawingSize() {
        return circularProgressGeometry == null
                ? size
                : AndroidUtilities.dp(circularProgressGeometry.getDiameterDp());
    }

    private void drawProgress(Canvas canvas, float left, float top, float right, float bottom) {
        boolean materialIndeterminate = circularProgressGeometry != null && noProgress;

        if (materialIndeterminate) {
            updateAnimation();
        }

        float strokeWidth = circularProgressGeometry == null
                ? progressStrokeWidth
                : AndroidUtilities.dp(circularProgressGeometry.getTrackThicknessDp());

        if (progressPaint.getStrokeWidth() != strokeWidth) {
            progressPaint.setStrokeWidth(strokeWidth);
        }

        float inset = circularProgressGeometry == null ? 0.0f : strokeWidth / 2.0f;
        cicleRect.set(left + inset, top + inset, right - inset, bottom - inset);

        if (materialIndeterminate) {
            drawCircularProgress(canvas, left, top, right, bottom, strokeWidth);
        } else {
            drawingCircleLenght = currentCircleLength;
            canvas.drawArc(cicleRect, radOffset, drawingCircleLenght, false, progressPaint);
        }

        if (!materialIndeterminate) {
            updateAnimation();
        }
    }

    private void drawCircularProgress(
            Canvas canvas,
            float left,
            float top,
            float right,
            float bottom,
            float strokeWidth
    ) {
        if (circularProgressAnimatorDurationScale == 0.0f) {
            drawCircularProgressStatic(canvas, left, top, right, bottom);

            return;
        }

        circularProgressGeometry.resolve((long) circularProgressTime, circularProgressResolved);

        float radius = (right - left - strokeWidth) / 2.0f;
        ensureCircularProgressPath(radius);

        float circumference = (float) (Math.PI * 2.0f * radius);
        float edgeInset = (AndroidUtilities.dp(circularProgressGeometry.getTrackGapDp())
                + strokeWidth / 2.0f) / circumference;
        float endFraction = circularProgressResolved.getEndFraction();
        float trackStart = endFraction + edgeInset;
        float trackSweep = 1.0f - endFraction - edgeInset * 2.0f;

        circularProgressTrackPaint.setStrokeWidth(strokeWidth);
        progressPaint.setStrokeWidth(strokeWidth);

        int save = canvas.save();
        canvas.translate((left + right) / 2.0f, (top + bottom) / 2.0f);
        canvas.rotate(circularProgressResolved.getRotationDegrees() - 90.0f);

        if (trackSweep > 0.0f) {
            cicleRect.set(-radius, -radius, radius, radius);
            canvas.drawArc(
                    cicleRect,
                    trackStart * 360.0f,
                    trackSweep * 360.0f,
                    false,
                    circularProgressTrackPaint
            );
        }

        float activeStart = edgeInset;
        float activeEnd = endFraction - edgeInset;
        drawingCircleLenght = Math.max(0.0f, activeEnd - activeStart) * 360.0f;

        if (activeEnd > activeStart) {
            circularProgressDisplayedPath.rewind();
            circularProgressPathMeasure.getSegment(
                    activeStart * circularProgressPathMeasure.getLength(),
                    activeEnd * circularProgressPathMeasure.getLength(),
                    circularProgressDisplayedPath,
                    true
            );
            canvas.drawPath(circularProgressDisplayedPath, progressPaint);
        }

        canvas.restoreToCount(save);
    }

    private void drawCircularProgressStatic(
            Canvas canvas,
            float left,
            float top,
            float right,
            float bottom
    ) {
        if (circularProgressStaticDrawable == null) {
            circularProgressStaticDrawable = getContext().getDrawable(
                    R.drawable.ic_fluxgram_material_progress_static).mutate();
        }

        circularProgressStaticDrawable.setTint(progressColor);
        circularProgressStaticDrawable.setBounds(
                Math.round(left),
                Math.round(top),
                Math.round(right),
                Math.round(bottom)
        );
        circularProgressStaticDrawable.draw(canvas);
        drawingCircleLenght = 360.0f;
    }

    private void ensureCircularProgressPath(float radius) {
        if (circularProgressPathRadius == radius) {
            return;
        }

        circularProgressPathRadius = radius;
        circularProgressPath.rewind();

        float wavelength = AndroidUtilities.dpf2(circularProgressGeometry.getWavelengthDp());
        float amplitude = AndroidUtilities.dpf2(circularProgressGeometry.getWaveAmplitudeDp());
        float circumference = (float) (Math.PI * 2.0f * radius);
        int cycleCount = Math.max(3, (int) (circumference / wavelength));
        int anchorCount = cycleCount * 2;
        float controlLength = circumference / cycleCount / 2.0f * 0.48f;

        float previousX = radius;
        float previousY = 0.0f;
        float previousTangentX = 0.0f;
        float previousTangentY = 1.0f;
        circularProgressPath.moveTo(previousX, previousY);

        for (int i = 1; i <= anchorCount; i++) {
            float angle = (float) (Math.PI * 2.0f * i / anchorCount);
            float anchorRadius = (i & 1) == 0 ? radius : radius - amplitude * 2.0f;
            float x = (float) Math.cos(angle) * anchorRadius;
            float y = (float) Math.sin(angle) * anchorRadius;
            float tangentX = -(float) Math.sin(angle);
            float tangentY = (float) Math.cos(angle);

            circularProgressPath.cubicTo(
                    previousX + previousTangentX * controlLength,
                    previousY + previousTangentY * controlLength,
                    x - tangentX * controlLength,
                    y - tangentY * controlLength,
                    x,
                    y
            );

            previousX = x;
            previousY = y;
            previousTangentX = tangentX;
            previousTangentY = tangentY;
        }

        circularProgressPathMeasure.setPath(circularProgressPath, false);
    }

    public boolean isCircle() {
        return Math.abs(drawingCircleLenght) >= 360;
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
