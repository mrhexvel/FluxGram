package org.telegram.ui.fluxgram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.fluxgram.AudioMiniPlayerPosition;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.DeterminateCircularProgressGeometry;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MiniPlayerGeometry;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.TypefaceSpan;

public final class MaterialAudioMiniPlayer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float MAGNET_ZONE = 0.18f;
    private static final float FLING_THRESHOLD = 800.0f;
    private static final float ACTIVATION_WAVE_INTENSITY = 0.16f;

    private final Theme.ResourcesProvider resourcesProvider;
    private final MiniPlayerGeometry geometry;
    private final String coverFilter;
    private final Runnable openPlayer;
    private final PositionListener positionListener;
    private final LinearLayout card;
    private final GradientDrawable cardBackground = new GradientDrawable();
    private final BackupImageView coverView;
    private final TextView titleView;
    private final RLottieImageView previousButton;
    private final PlaybackRingView playButton;
    private final RLottieImageView nextButton;
    private final int touchSlop;
    private final int[] cardLocation = new int[2];
    private final Runnable startDragging = this::startDragging;
    private final Runnable releaseActivationRipple;

    private MessageObject currentMessage;
    private CharSequence titleText;
    private CharSequence artistText;
    private int artistColor;
    private ValueAnimator visibilityAnimator;
    private SpringAnimation positionSpring;
    private VelocityTracker velocityTracker;
    private AudioMiniPlayerPosition settledPosition;
    private AudioMiniPlayerPosition targetPosition;
    private boolean shown;
    private boolean dragging;
    private boolean dragLifted;
    private boolean hostBoundsReady;
    private float showProgress;
    private float placementProgress;
    private float hostTop;
    private float hostBottom;
    private float hostFactor = 1.0f;
    private float topZone;
    private float topShift;
    private float topVisibility = 1.0f;
    private float downRawX;
    private float downRawY;
    private float lastRawY;
    private float dragStartRawY;
    private float dragStartProgress;
    private int magnetTarget;
    private Runnable offsetListener;

    public interface PositionListener {
        void onPositionSettled(AudioMiniPlayerPosition position);
    }

    public MaterialAudioMiniPlayer(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            MiniPlayerGeometry geometry,
            Runnable openPlayer,
            AudioMiniPlayerPosition initialPosition,
            PositionListener positionListener
    ) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.geometry = geometry;
        coverFilter = geometry.getCoverSizeDp() + "_" + geometry.getCoverSizeDp();
        this.openPlayer = openPlayer;
        this.positionListener = positionListener;
        settledPosition = initialPosition == null
                ? AudioMiniPlayerPosition.BOTTOM
                : initialPosition;
        targetPosition = settledPosition;
        placementProgress = settledPosition == AudioMiniPlayerPosition.TOP ? 1.0f : 0.0f;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClipChildren(false);
        setClipToPadding(false);

        card = new LinearLayout(context);
        releaseActivationRipple = () -> card.setPressed(false);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), 0, dp(6), 0);
        cardBackground.setCornerRadius(dp(geometry.getCornerRadiusDp()));
        card.setBackground(cardBackground);
        card.setClipToOutline(true);
        card.setElevation(dp(6));
        card.setOnClickListener(view -> {
            if (currentMessage != null) {
                openPlayer.run();
            }
        });
        card.setOnTouchListener(this::onCardTouch);
        ViewCompat.addAccessibilityAction(
                card,
                getString(R.string.FluxGramMoveUp),
                (view, arguments) -> {
                    settleTo(AudioMiniPlayerPosition.TOP);
                    return true;
                }
        );
        ViewCompat.addAccessibilityAction(
                card,
                getString(R.string.FluxGramMoveDown),
                (view, arguments) -> {
                    settleTo(AudioMiniPlayerPosition.BOTTOM);
                    return true;
                }
        );
        addView(card, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                geometry.getHeightDp(),
                Gravity.TOP,
                geometry.getHorizontalMarginDp(),
                0,
                geometry.getHorizontalMarginDp(),
                0
        ));

        coverView = new BackupImageView(context);
        coverView.setRoundRadius(dp(geometry.getCoverRadiusDp()));
        card.addView(coverView, LayoutHelper.createLinear(
                geometry.getCoverSizeDp(),
                geometry.getCoverSizeDp()
        ));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(titleView, LayoutHelper.createLinear(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f,
                12,
                0,
                4,
                0
        ));

        previousButton = createNavigationButton(context, false, R.string.AccDescrPrevious);
        previousButton.setOnClickListener(view -> {
            MediaController.getInstance().playPreviousMessage();
            previousButton.setProgress(0.0f);
            previousButton.playAnimation();
        });
        card.addView(previousButton, LayoutHelper.createLinear(
                geometry.getControlButtonWidthDp(),
                48
        ));

        playButton = new PlaybackRingView(context, geometry.getProgressGeometry());
        playButton.setContentDescription(getString(R.string.AccActionPlay));
        playButton.setOnClickListener(view -> togglePlayback());
        card.addView(playButton, LayoutHelper.createLinear(
                geometry.getPlayButtonWidthDp(),
                48
        ));

        nextButton = createNavigationButton(context, true, R.string.Next);
        nextButton.setOnClickListener(view -> {
            MediaController.getInstance().playNextMessage();
            nextButton.setProgress(0.0f);
            nextButton.playAnimation();
        });
        card.addView(nextButton, LayoutHelper.createLinear(
                geometry.getControlButtonWidthDp(),
                48
        ));

        updateColors();
        setVisibility(GONE);
    }

    private RLottieImageView createNavigationButton(Context context, boolean next, int description) {
        RLottieImageView button = new RLottieImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setAnimation(R.raw.player_prev, 20, 20);
        button.setRotation(next ? 180.0f : 0.0f);
        button.setContentDescription(getString(description));
        return button;
    }

    private void togglePlayback() {
        MediaController mediaController = MediaController.getInstance();
        MessageObject messageObject = mediaController.getPlayingMessageObject();
        if (messageObject == null || mediaController.isDownloadingCurrentMessage()) {
            return;
        }

        if (mediaController.isMessagePaused()) {
            mediaController.playMessage(messageObject);
        } else {
            mediaController.pauseMessage(messageObject);
        }
    }

    public int getBottomHeightDp() {
        return geometry.getHeightDp();
    }

    public int getBottomVerticalMarginDp() {
        return geometry.getVerticalMarginDp();
    }

    public void setHostBounds(float top, float bottom, float factor) {
        float previousFactor = hostFactor;
        hostTop = Math.min(top, bottom);
        hostBottom = Math.max(top, bottom);
        hostBoundsReady = bottom > top;
        hostFactor = factor;
        applyVisibility();
        if (previousFactor != factor) {
            notifyOffsetChanged();
        }
    }

    public void setTopState(float anchor, float zone, float shift, float visibility) {
        float top = Math.min(anchor + dp(geometry.getTopGapDp()), hostBottom);
        float boundedZone = clamp(zone, 0.0f, 1.0f);
        float boundedVisibility = clamp(visibility, 0.0f, 1.0f);
        if (hostTop == top
                && topZone == boundedZone
                && topShift == shift
                && topVisibility == boundedVisibility) {
            return;
        }

        hostTop = top;
        topZone = boundedZone;
        topShift = shift;
        topVisibility = boundedVisibility;
        applyVisibility();
    }

    public void setOffsetListener(Runnable listener) {
        offsetListener = listener;
    }

    public float getVisibleOffset(boolean chats) {
        return dp(geometry.getHeightDp() + 8)
                * showProgress
                * hostFactor
                * (chats ? 1.0f - placementProgress : 1.0f);
    }

    public int getTargetOffset(boolean chats) {
        return shown
                && (!chats || targetPosition == AudioMiniPlayerPosition.BOTTOM)
                && hostFactor > 0.5f
                ? dp(geometry.getHeightDp() + 8)
                : 0;
    }

    public int getTargetTopOffset(boolean chats) {
        return chats
                && shown
                && targetPosition == AudioMiniPlayerPosition.TOP
                ? dp(geometry.getHeightDp() + 16)
                : 0;
    }

    private boolean onCardTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = false;
            magnetTarget = 0;
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            lastRawY = downRawY;
            recycleVelocityTracker();
            velocityTracker = VelocityTracker.obtain();
            addVelocityMovement(event);
            postDelayed(startDragging, ViewConfiguration.getLongPressTimeout());
            return false;
        }

        addVelocityMovement(event);

        if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            removeCallbacks(startDragging);
            boolean wasDragging = dragging;
            if (dragging) {
                dragging = false;
                settleTo(placementProgress >= 0.5f
                        ? AudioMiniPlayerPosition.TOP
                        : AudioMiniPlayerPosition.BOTTOM);
            }
            recycleVelocityTracker();
            return wasDragging;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            lastRawY = event.getRawY();
            if (!dragging
                    && (Math.abs(event.getRawX() - downRawX) > touchSlop
                    || Math.abs(lastRawY - downRawY) > touchSlop)) {
                removeCallbacks(startDragging);
            }

            if (dragging) {
                updateDrag(lastRawY);
                return true;
            }

            return false;
        }

        removeCallbacks(startDragging);
        if (action == MotionEvent.ACTION_UP && dragging) {
            float velocityY = 0.0f;
            if (velocityTracker != null) {
                velocityTracker.computeCurrentVelocity(1000);
                velocityY = velocityTracker.getYVelocity();
            }
            settleAfterDrag(velocityY);
            recycleVelocityTracker();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL && dragging) {
            settleTo(placementProgress >= 0.5f
                    ? AudioMiniPlayerPosition.TOP
                    : AudioMiniPlayerPosition.BOTTOM);
            dragging = false;
            recycleVelocityTracker();
            return true;
        }

        recycleVelocityTracker();
        return false;
    }

    private void startDragging() {
        if (!shown
                || !card.isPressed()
                || hostFactor < 0.99f
                || topVisibility < 0.99f
                || topZone < 0.99f
                || hostBottom - hostTop <= dp(48)) {
            return;
        }

        dragging = true;
        cancelPositionSpring();
        dragStartRawY = lastRawY;
        dragStartProgress = placementProgress;
        magnetTarget = placementProgress >= 0.5f ? 1 : -1;
        animateDragActivation();
        card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void updateDrag(float rawY) {
        float range = Math.max(1.0f, hostBottom - hostTop);
        float rawProgress = clamp(
                dragStartProgress + (dragStartRawY - rawY) / range,
                0.0f,
                1.0f
        );
        int nextMagnetTarget = rawProgress <= MAGNET_ZONE
                ? -1
                : rawProgress >= 1.0f - MAGNET_ZONE ? 1 : 0;
        if (nextMagnetTarget != 0 && nextMagnetTarget != magnetTarget) {
            card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        magnetTarget = nextMagnetTarget;

        if (rawProgress < MAGNET_ZONE) {
            float normalized = rawProgress / MAGNET_ZONE;
            rawProgress = MAGNET_ZONE * normalized * normalized;
        } else if (rawProgress > 1.0f - MAGNET_ZONE) {
            float normalized = (1.0f - rawProgress) / MAGNET_ZONE;
            rawProgress = 1.0f - MAGNET_ZONE * normalized * normalized;
        }

        setPlacementProgress(rawProgress);
    }

    private void settleAfterDrag(float velocityY) {
        AudioMiniPlayerPosition position;
        if (velocityY <= -FLING_THRESHOLD) {
            position = AudioMiniPlayerPosition.TOP;
        } else if (velocityY >= FLING_THRESHOLD) {
            position = AudioMiniPlayerPosition.BOTTOM;
        } else {
            position = placementProgress >= 0.5f
                    ? AudioMiniPlayerPosition.TOP
                    : AudioMiniPlayerPosition.BOTTOM;
        }

        dragging = false;
        settleTo(position);
    }

    private void settleTo(AudioMiniPlayerPosition position) {
        setDragLifted(false, true);
        targetPosition = position;
        notifyOffsetChanged();
        float target = position == AudioMiniPlayerPosition.TOP ? 1.0f : 0.0f;
        if (!isAttachedToWindow() || !areAnimationsEnabled()) {
            setPlacementProgress(target);
            finishPositionSettle(position);
            return;
        }

        cancelPositionSpring();
        FloatValueHolder value = new FloatValueHolder(placementProgress);
        positionSpring = new SpringAnimation(value);
        positionSpring.setMinimumVisibleChange(0.001f);
        positionSpring.setSpring(new SpringForce(target)
                .setStiffness(520.0f)
                .setDampingRatio(0.82f));
        positionSpring.addUpdateListener((animation, current, velocity) ->
                setPlacementProgress(clamp(current, 0.0f, 1.0f)));
        positionSpring.addEndListener((animation, canceled, current, velocity) -> {
            if (positionSpring == animation) {
                positionSpring = null;
            }
            if (!canceled) {
                setPlacementProgress(target);
                finishPositionSettle(position);
            }
        });
        positionSpring.start();
    }

    private void finishPositionSettle(AudioMiniPlayerPosition position) {
        AudioMiniPlayerPosition previous = settledPosition;
        settledPosition = position;
        targetPosition = position;
        notifyOffsetChanged();
        if (previous != position && positionListener != null) {
            positionListener.onPositionSettled(position);
        }
    }

    private void setPlacementProgress(float progress) {
        if (placementProgress == progress) {
            return;
        }

        placementProgress = progress;
        applyVisibility();
        notifyOffsetChanged();
    }

    private void cancelPositionSpring() {
        if (positionSpring != null) {
            positionSpring.cancel();
            positionSpring = null;
        }
    }

    private void animateDragActivation() {
        card.getLocationOnScreen(cardLocation);
        float localX = clamp(downRawX - cardLocation[0], 0.0f, card.getWidth());
        float localY = clamp(downRawY - cardLocation[1], 0.0f, card.getHeight());
        card.setPressed(false);
        card.drawableHotspotChanged(localX, localY);
        card.setPressed(true);
        removeCallbacks(releaseActivationRipple);
        postDelayed(releaseActivationRipple, 90);

        setDragLifted(true, true);
        if (areAnimationsEnabled() && !LiteMode.isPowerSaverApplied()) {
            card.getLocationInWindow(cardLocation);
            LaunchActivity.makeRipple(
                    cardLocation[0] + card.getWidth() / 2.0f,
                    cardLocation[1] + card.getHeight() / 2.0f,
                    ACTIVATION_WAVE_INTENSITY
            );
        }
    }

    private void setDragLifted(boolean lifted, boolean animated) {
        if (dragLifted == lifted) {
            return;
        }

        dragLifted = lifted;
        card.animate().cancel();
        float scale = lifted ? 1.02f : 1.0f;
        float translationZ = lifted ? dp(3) : 0.0f;
        if (!animated || !areAnimationsEnabled()) {
            card.setScaleX(scale);
            card.setScaleY(scale);
            card.setTranslationZ(translationZ);
            return;
        }

        card.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(translationZ)
                .setDuration(lifted ? 160 : 220)
                .start();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void addVelocityMovement(MotionEvent event) {
        if (velocityTracker == null) {
            return;
        }

        MotionEvent rawEvent = MotionEvent.obtain(event);
        rawEvent.offsetLocation(
                event.getRawX() - event.getX(),
                event.getRawY() - event.getY()
        );
        velocityTracker.addMovement(rawEvent);
        rawEvent.recycle();
    }

    private static float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean areAnimationsEnabled() {
        return AndroidUtilities.getAnimatorDurationScale() > 0.0f;
    }

    public void updateColors() {
        int backgroundColor = getThemedColor(Theme.key_inappPlayerBackground);
        int titleColor = getThemedColor(Theme.key_inappPlayerTitle);
        int artistColor = getThemedColor(Theme.key_inappPlayerPerformer);
        int controlColor = getThemedColor(Theme.key_inappPlayerPlayPause);
        int rippleColor = ColorUtils.setAlphaComponent(controlColor, 0x1f);

        cardBackground.setColor(backgroundColor);
        titleView.setTextColor(titleColor);
        this.artistColor = ColorUtils.setAlphaComponent(artistColor, 0xcc);
        bindText();
        previousButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        nextButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        previousButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        nextButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        playButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        playButton.setColors(
                controlColor,
                ColorUtils.setAlphaComponent(controlColor, 46)
        );
        card.setForeground(Theme.createSelectorDrawable(rippleColor, Theme.RIPPLE_MASK_ALL));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int shadowColor = ColorUtils.setAlphaComponent(Color.BLACK, 48);
            card.setOutlineAmbientShadowColor(shadowColor);
            card.setOutlineSpotShadowColor(shadowColor);
        }
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter notificationCenter = NotificationCenter.getInstance(account);
            notificationCenter.addObserver(this, NotificationCenter.messagePlayingDidStart);
            notificationCenter.addObserver(this, NotificationCenter.messagePlayingDidReset);
            notificationCenter.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            notificationCenter.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            notificationCenter.addObserver(this, NotificationCenter.fileLoaded);
        }

        update(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(startDragging);
        removeCallbacks(releaseActivationRipple);
        card.setPressed(false);
        AudioMiniPlayerPosition detachedTarget = targetPosition;
        boolean settlingPosition = positionSpring != null;
        cancelPositionSpring();
        recycleVelocityTracker();
        dragging = false;
        setDragLifted(false, false);
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }
        showProgress = shown ? 1.0f : 0.0f;
        if (settlingPosition) {
            setPlacementProgress(detachedTarget == AudioMiniPlayerPosition.TOP ? 1.0f : 0.0f);
            finishPositionSettle(detachedTarget);
        }

        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            NotificationCenter notificationCenter = NotificationCenter.getInstance(account);
            notificationCenter.removeObserver(this, NotificationCenter.messagePlayingDidStart);
            notificationCenter.removeObserver(this, NotificationCenter.messagePlayingDidReset);
            notificationCenter.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            notificationCenter.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            notificationCenter.removeObserver(this, NotificationCenter.fileLoaded);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            playButton.progressChanged();
        } else if (id == NotificationCenter.fileLoaded) {
            MessageObject messageObject = currentMessage;
            if (messageObject != null
                    && messageObject.getDocument() != null
                    && TextUtils.equals(
                            (String) args[0],
                            FileLoader.getAttachFileName(messageObject.getDocument())
                    )) {
                updateCover(messageObject);
            }
        } else {
            update(true);
        }
    }

    private void update(boolean animated) {
        MediaController mediaController = MediaController.getInstance();
        MessageObject messageObject = mediaController.getPlayingMessageObject();
        boolean shouldShow = messageObject != null
                && messageObject.isMusic()
                && messageObject.getId() != 0;

        if (shouldShow) {
            bind(messageObject);
        }

        boolean playing = shouldShow && !mediaController.isMessagePaused();
        playButton.setPlaying(playing, animated);
        playButton.setContentDescription(getString(
                playing ? R.string.AccActionPause : R.string.AccActionPlay
        ));
        setShown(shouldShow, animated);
    }

    private void bind(MessageObject messageObject) {
        boolean changed = currentMessage != messageObject;
        currentMessage = messageObject;
        titleText = messageObject.getMusicTitle();
        artistText = messageObject.getMusicAuthor();
        bindText();
        card.setContentDescription(TextUtils.concat(
                titleText,
                ", ",
                artistText
        ));
        playButton.setMessage(messageObject);

        if (changed) {
            updateCover(messageObject);
        }
    }

    private void bindText() {
        SpannableStringBuilder text = new SpannableStringBuilder(
                titleText == null ? "" : titleText
        );
        if (text.length() > 0) {
            text.setSpan(
                    new TypefaceSpan(AndroidUtilities.bold()),
                    0,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        if (!TextUtils.isEmpty(artistText)) {
            int artistStart = text.length();
            text.append(" · ").append(artistText);
            text.setSpan(
                    new ForegroundColorSpan(artistColor),
                    artistStart,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        titleView.setText(text);
    }

    private void updateCover(MessageObject messageObject) {
        coverView.getImageReceiver().setCurrentAccount(messageObject.currentAccount);
        AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            return;
        }

        TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document == null
                ? null
                : FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 96);
        ImageLocation thumbLocation = thumb == null
                ? null
                : ImageLocation.getForDocument(thumb, document);
        String artworkUrl = messageObject.getArtworkUrl(true);
        ImageLocation artworkLocation = TextUtils.isEmpty(artworkUrl)
                ? null
                : ImageLocation.getForPath(artworkUrl);

        coverView.setImage(
                artworkLocation,
                coverFilter,
                thumbLocation,
                coverFilter,
                getContext().getDrawable(R.drawable.nocover_small),
                messageObject
        );
    }

    private void setShown(boolean value, boolean animated) {
        if (shown == value && visibilityAnimator == null) {
            applyVisibility();
            notifyOffsetChanged();
            return;
        }

        shown = value;
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }

        float target = value ? 1.0f : 0.0f;
        if (!animated || !isAttachedToWindow()) {
            showProgress = target;
            applyVisibility();
            notifyOffsetChanged();
            return;
        }

        visibilityAnimator = ValueAnimator.ofFloat(showProgress, target);
        visibilityAnimator.addUpdateListener(animation -> {
            showProgress = (float) animation.getAnimatedValue();
            applyVisibility();
            notifyOffsetChanged();
        });
        visibilityAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (visibilityAnimator == animation) {
                    visibilityAnimator = null;
                }
            }
        });
        visibilityAnimator.setDuration(value ? 450 : 300);
        visibilityAnimator.start();
    }

    private void applyVisibility() {
        if (!hostBoundsReady) {
            setVisibility(GONE);
            return;
        }

        boolean top = placementProgress > 0.0f && topZone >= 0.5f;
        float swap = Math.min(1.0f, Math.abs(topZone - 0.5f) * 2.0f);
        float positionVisibility = top
                ? lerp(hostFactor, topVisibility, placementProgress)
                : hostFactor;
        float progress = showProgress * positionVisibility * swap;
        float position = top
                ? lerp(hostBottom, hostTop, placementProgress)
                : hostBottom;
        float direction = top ? 1.0f - 2.0f * placementProgress : 1.0f;
        card.setTranslationX(top ? topShift : 0.0f);
        card.setTranslationY(position + dp(24) * (1.0f - progress) * direction);
        card.setAlpha(progress);
        setVisibility(progress > 0.0f ? VISIBLE : GONE);
    }

    private void notifyOffsetChanged() {
        if (offsetListener != null) {
            offsetListener.run();
        }
    }

    private static final class PlaybackRingView extends View {
        private static final long WAVE_FRAME_DELAY_MS = 33;

        private final DeterminateCircularProgressGeometry geometry;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path progressPath = new Path();
        private final PlayPauseDrawable playPauseDrawable = new PlayPauseDrawable(16);
        private final Runnable waveFrame = this::invalidate;

        private MessageObject messageObject;
        private boolean playing;
        private float amplitude;
        private float phase;
        private long lastFrameTime;
        private float drawnProgress = -1.0f;

        PlaybackRingView(Context context, DeterminateCircularProgressGeometry geometry) {
            super(context);
            this.geometry = geometry;

            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(dpf2(geometry.getTrackThicknessDp()));
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(dpf2(geometry.getTrackThicknessDp()));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeJoin(Paint.Join.ROUND);
            playPauseDrawable.setParent(this);
        }

        void setColors(int activeColor, int inactiveColor) {
            trackPaint.setColor(inactiveColor);
            progressPaint.setColor(activeColor);
            playPauseDrawable.setColor(activeColor);
            invalidate();
        }

        void setMessage(MessageObject messageObject) {
            this.messageObject = messageObject;
            drawnProgress = -1.0f;
            invalidate();
        }

        void setPlaying(boolean playing, boolean animated) {
            if (this.playing == playing) {
                return;
            }

            this.playing = playing;
            playPauseDrawable.setPause(playing, animated);
            lastFrameTime = 0;
            invalidate();
        }

        void progressChanged() {
            float progress = resolveProgress();
            float radius = dpf2(geometry.getDiameterDp()) / 2.0f;
            if (amplitude > 0.0f
                    || Math.abs(progress - drawnProgress) * TWO_PI * radius >= 1.0f) {
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(waveFrame);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            long now = SystemClock.elapsedRealtime();
            float deltaSeconds = lastFrameTime == 0
                    ? 0.016f
                    : Math.min(0.05f, (now - lastFrameTime) / 1000.0f);
            lastFrameTime = now;

            float targetAmplitude = playing && !LiteMode.isPowerSaverApplied()
                    ? dpf2(geometry.getWaveAmplitudeDp())
                    : 0.0f;
            amplitude += (targetAmplitude - amplitude)
                    * (1.0f - (float) Math.pow(0.85, deltaSeconds / 0.04f));
            if (Math.abs(amplitude - targetAmplitude) < dpf2(0.03f)) {
                amplitude = targetAmplitude;
            }
            if (amplitude > 0.0f) {
                phase = (phase + 6.0f * deltaSeconds) % TWO_PI;
            }

            float centerX = getWidth() / 2.0f;
            float centerY = getHeight() / 2.0f;
            float radius = dpf2(geometry.getDiameterDp()) / 2.0f;
            canvas.drawCircle(centerX, centerY, radius, trackPaint);

            float progress = resolveProgress();
            drawnProgress = progress;
            float sweep = 360.0f * progress;
            if (sweep > 0.5f) {
                float circumference = TWO_PI * radius;
                int waveCount = Math.max(3, Math.round(
                        circumference / dpf2(geometry.getWavelengthDp())
                ));
                progressPath.rewind();
                boolean first = true;
                for (float degrees = 0.0f; ; degrees += 3.0f) {
                    boolean last = degrees >= sweep;
                    float boundedDegrees = last ? sweep : degrees;
                    double radians = Math.toRadians(boundedDegrees - 90.0f);
                    float waveRadius = radius + amplitude * (float) Math.sin(
                            waveCount * Math.toRadians(boundedDegrees) - phase
                    );
                    float x = centerX + waveRadius * (float) Math.cos(radians);
                    float y = centerY + waveRadius * (float) Math.sin(radians);
                    if (first) {
                        progressPath.moveTo(x, y);
                        first = false;
                    } else {
                        progressPath.lineTo(x, y);
                    }
                    if (last) {
                        break;
                    }
                }
                canvas.drawPath(progressPath, progressPaint);
            }

            int iconHalfSize = dp(8);
            playPauseDrawable.setBounds(
                    Math.round(centerX) - iconHalfSize,
                    Math.round(centerY) - iconHalfSize,
                    Math.round(centerX) + iconHalfSize,
                    Math.round(centerY) + iconHalfSize
            );
            playPauseDrawable.draw(canvas);

            removeCallbacks(waveFrame);
            if (targetAmplitude != amplitude) {
                postInvalidateOnAnimation();
            } else if (amplitude > 0.0f) {
                postDelayed(waveFrame, WAVE_FRAME_DELAY_MS);
            } else {
                lastFrameTime = 0;
            }
        }

        private float resolveProgress() {
            return messageObject == null
                    ? 0.0f
                    : Math.max(0.0f, Math.min(1.0f, messageObject.audioProgress));
        }
    }
}
