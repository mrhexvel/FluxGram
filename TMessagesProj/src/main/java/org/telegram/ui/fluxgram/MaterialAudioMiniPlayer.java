package org.telegram.ui.fluxgram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TouchDelegate;
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
import org.telegram.messenger.Utilities;
import org.telegram.messenger.fluxgram.AudioMiniPlayerPosition;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
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
    private static final float DISMISS_FLING_THRESHOLD = 1000.0f;
    private static final float ACTIVATION_WAVE_INTENSITY = 0.16f;
    private static final int DISMISS_NONE = 0;
    private static final int DISMISS_DOWN = 1;
    private static final int DISMISS_UP = 2;
    private static final int DISMISS_LEFT = 3;
    private static final int DISMISS_RIGHT = 4;

    private final Theme.ResourcesProvider resourcesProvider;
    private final MiniPlayerGeometry geometry;
    private final String coverFilter;
    private final String middleCoverFilter = "44_44";
    private final Runnable openPlayer;
    private final PositionListener positionListener;
    private final MorphingCardView morphingCard;
    private final DraggableCard card;
    private final GradientDrawable cardBackground = new GradientDrawable();
    private final BackupImageView coverView;
    private final TextView titleView;
    private final RLottieImageView previousButton;
    private final PlaybackRingView playButton;
    private final RLottieImageView nextButton;
    private final DraggableCard middleCard;
    private final GradientDrawable middleCardBackground = new GradientDrawable();
    private final BackupImageView middleCoverView;
    private final TextView middleTitleView;
    private final TextView middleArtistView;
    private final RLottieImageView middlePreviousButton;
    private final PlaybackRingView middlePlayButton;
    private final RLottieImageView middleNextButton;
    private final DraggableCard topCard;
    private final LinearLayout topCardContent;
    private final GradientDrawable topCardBackground = new GradientDrawable();
    private final BackupImageView topCoverView;
    private final TextView topTitleView;
    private final PlaybackWaveView topWaveView;
    private final PlaybackButtonView topPlayButton;
    private final int touchSlop;
    private final int[] cardLocation = new int[2];
    private final RectF middleBounds = new RectF();
    private final RectF topBounds = new RectF();
    private final Runnable startDragging = this::startDragging;
    private final Runnable releaseActivationRipple;
    private final ContentObserver animationScaleObserver;
    private final Utilities.Callback<Boolean> powerSaverCallback;

    private MessageObject currentMessage;
    private CharSequence titleText;
    private CharSequence artistText;
    private int artistColor;
    private int dismissDirection;
    private ValueAnimator visibilityAnimator;
    private ValueAnimator dismissAnimator;
    private SpringAnimation positionSpring;
    private VelocityTracker velocityTracker;
    private AudioMiniPlayerPosition settledPosition;
    private AudioMiniPlayerPosition targetPosition;
    private boolean shown;
    private boolean dragging;
    private boolean dismissDragging;
    private boolean touchActive;
    private boolean hostBoundsReady;
    private float showProgress;
    private float placementProgress;
    private float dismissOffsetX;
    private float dismissOffsetY;
    private float dismissAlpha = 1.0f;
    private float hostBottom;
    private float hostFactor = 1.0f;
    private float topZone;
    private float topVisibility = 1.0f;
    private float downRawX;
    private float downRawY;
    private float lastRawY;
    private float dragStartRawY;
    private float dragStartProgress;
    private int magnetTarget;
    private int moveUpAccessibilityActionId = -1;
    private int moveUpMiddleAccessibilityActionId = -1;
    private Runnable offsetListener;
    private View dragCard;

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
        placementProgress = getPositionProgress(settledPosition);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        setClipChildren(false);
        setClipToPadding(false);

        morphingCard = new MorphingCardView(context);
        addView(morphingCard, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));

        card = new DraggableCard(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), 0, dp(6), 0);
        cardBackground.setCornerRadius(dp(geometry.getCornerRadiusDp()));
        card.setBackground(cardBackground);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setClipToOutline(false);
        card.setElevation(dp(6));
        card.setOnClickListener(view -> {
            if (currentMessage != null) {
                openPlayer.run();
            }
        });
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

        middleCard = new DraggableCard(context);
        middleCard.setOrientation(LinearLayout.HORIZONTAL);
        middleCard.setGravity(Gravity.CENTER_VERTICAL);
        middleCard.setPadding(dp(10), 0, dp(6), 0);
        middleCardBackground.setCornerRadius(dp(20));
        middleCard.setBackground(middleCardBackground);
        middleCard.setClipChildren(false);
        middleCard.setClipToPadding(false);
        middleCard.setClipToOutline(false);
        middleCard.setElevation(dp(6));
        middleCard.setOnClickListener(view -> {
            if (currentMessage != null) {
                openPlayer.run();
            }
        });
        ViewCompat.addAccessibilityAction(
                middleCard,
                getString(R.string.FluxGramMoveDown),
                (view, arguments) -> {
                    settleTo(AudioMiniPlayerPosition.BOTTOM);
                    return true;
                }
        );
        addView(middleCard, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                64,
                Gravity.TOP,
                12,
                0,
                12,
                0
        ));

        middleCoverView = new BackupImageView(context);
        middleCoverView.setRoundRadius(dp(12));
        middleCard.addView(middleCoverView, LayoutHelper.createLinear(44, 44));

        LinearLayout middleTexts = new LinearLayout(context);
        middleTexts.setOrientation(LinearLayout.VERTICAL);
        middleTitleView = new TextView(context);
        middleTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        middleTitleView.setTypeface(AndroidUtilities.bold());
        middleTitleView.setSingleLine(true);
        middleTitleView.setEllipsize(TextUtils.TruncateAt.END);
        middleTexts.addView(middleTitleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
        middleArtistView = new TextView(context);
        middleArtistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        middleArtistView.setSingleLine(true);
        middleArtistView.setEllipsize(TextUtils.TruncateAt.END);
        middleTexts.addView(middleArtistView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                0,
                2,
                0,
                0
        ));
        middleCard.addView(middleTexts, LayoutHelper.createLinear(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f,
                12,
                0,
                4,
                0
        ));

        middlePreviousButton = createNavigationButton(context, false, R.string.AccDescrPrevious);
        middlePreviousButton.setOnClickListener(view -> {
            MediaController.getInstance().playPreviousMessage();
            middlePreviousButton.setProgress(0.0f);
            middlePreviousButton.playAnimation();
        });
        middleCard.addView(middlePreviousButton, LayoutHelper.createLinear(44, 48));

        middlePlayButton = new PlaybackRingView(context, geometry.getProgressGeometry());
        middlePlayButton.setContentDescription(getString(R.string.AccActionPlay));
        middlePlayButton.setOnClickListener(view -> togglePlayback());
        middleCard.addView(middlePlayButton, LayoutHelper.createLinear(48, 48));

        middleNextButton = createNavigationButton(context, true, R.string.Next);
        middleNextButton.setOnClickListener(view -> {
            MediaController.getInstance().playNextMessage();
            middleNextButton.setProgress(0.0f);
            middleNextButton.playAnimation();
        });
        middleCard.addView(middleNextButton, LayoutHelper.createLinear(44, 48));

        topCard = new DraggableCard(context);
        topCard.setGravity(Gravity.CENTER);
        topCard.setOnClickListener(view -> {
            if (currentMessage != null) {
                openPlayer.run();
            }
        });
        ViewCompat.addAccessibilityAction(
                topCard,
                getString(R.string.FluxGramMoveDown),
                (view, arguments) -> {
                    settleTo(middleBounds.isEmpty()
                            ? AudioMiniPlayerPosition.BOTTOM
                            : AudioMiniPlayerPosition.MIDDLE);
                    return true;
                }
        );
        addView(topCard, LayoutHelper.createFrame(148, 48));

        topCardContent = new LinearLayout(context);
        topCardContent.setOrientation(LinearLayout.HORIZONTAL);
        topCardContent.setGravity(Gravity.CENTER_VERTICAL);
        topCardBackground.setCornerRadius(dp(18));
        topCardContent.setBackground(new InsetDrawable(topCardBackground, 0, dp(6), 0, dp(6)));
        topCardContent.setPadding(dp(10), 0, dp(8), 0);
        topCardContent.setClipToOutline(true);
        topCardContent.setElevation(dp(6));
        topCard.addView(topCardContent, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                48
        ));

        topCoverView = new BackupImageView(context);
        topCoverView.setRoundRadius(dp(12));
        topCardContent.addView(topCoverView, LayoutHelper.createLinear(24, 24));

        topTitleView = new TextView(context);
        topTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        topTitleView.setTypeface(AndroidUtilities.regular());
        topTitleView.setSingleLine(true);
        topTitleView.setEllipsize(TextUtils.TruncateAt.END);
        topCardContent.addView(topTitleView, LayoutHelper.createLinear(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f,
                6,
                0,
                2,
                0
        ));

        topWaveView = new PlaybackWaveView(context);
        topCardContent.addView(topWaveView, LayoutHelper.createLinear(24, 20));

        topPlayButton = new PlaybackButtonView(context);
        topPlayButton.setContentDescription(getString(R.string.AccActionPlay));
        topPlayButton.setOnClickListener(view -> togglePlayback());
        topCardContent.addView(topPlayButton, LayoutHelper.createLinear(22, 48));
        topCardContent.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            Rect bounds = new Rect();
            topPlayButton.getHitRect(bounds);
            bounds.left -= dp(26);
            bounds.top = 0;
            bounds.bottom = topCardContent.getHeight();
            topCardContent.setTouchDelegate(new TouchDelegate(bounds, topPlayButton));
        });
        animationScaleObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                topWaveView.invalidate();
                playButton.invalidate();
                middlePlayButton.invalidate();
            }
        };
        powerSaverCallback = applied -> {
            topWaveView.invalidate();
            playButton.invalidate();
            middlePlayButton.invalidate();
        };
        releaseActivationRipple = () -> {
            card.setPressed(false);
            middleCard.setPressed(false);
            topCard.setPressed(false);
        };

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
        hostBottom = Math.max(top, bottom);
        hostBoundsReady = bottom > top;
        hostFactor = factor;
        applyVisibility();
        if (previousFactor != factor) {
            notifyOffsetChanged();
        }
    }

    public void setMiddleState(RectF bounds, float zone, float visibility) {
        float boundedZone = clamp(zone, 0.0f, 1.0f);
        float boundedVisibility = clamp(visibility, 0.0f, 1.0f);
        boolean availabilityChanged = middleBounds.isEmpty() != bounds.isEmpty();
        boolean boundsChanged = !middleBounds.equals(bounds);
        boolean stateChanged = topZone != boundedZone
                || topVisibility != boundedVisibility;
        if (!boundsChanged
                && topZone == boundedZone
                && topVisibility == boundedVisibility) {
            return;
        }

        middleBounds.set(bounds);
        updateMoveAccessibilityActions();
        topZone = boundedZone;
        topVisibility = boundedVisibility;
        interruptHiddenPlacement();
        if (boundsChanged) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) middleCard.getLayoutParams();
            params.width = Math.max(1, Math.round(bounds.width()));
            params.height = bounds.isEmpty() ? 1 : dp(64);
            params.leftMargin = Math.round(bounds.left) - getPaddingLeft();
            params.topMargin = Math.round(bounds.top) - getPaddingTop();
            middleCard.setLayoutParams(params);
        }
        applyVisibility();
        if (availabilityChanged || stateChanged) {
            notifyOffsetChanged();
        }
    }

    public void setTopState(RectF bounds, float zone, float visibility) {
        float boundedZone = clamp(zone, 0.0f, 1.0f);
        float boundedVisibility = clamp(visibility, 0.0f, 1.0f);
        boolean availabilityChanged = topBounds.isEmpty() != bounds.isEmpty();
        boolean boundsChanged = !topBounds.equals(bounds);
        if (!boundsChanged
                && topZone == boundedZone
                && topVisibility == boundedVisibility) {
            return;
        }

        topBounds.set(bounds);
        updateMoveAccessibilityActions();
        topZone = boundedZone;
        topVisibility = boundedVisibility;
        interruptHiddenPlacement();
        if (boundsChanged) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) topCard.getLayoutParams();
            params.width = Math.max(1, Math.round(bounds.width()));
            params.height = bounds.isEmpty() ? 1 : dp(48);
            params.leftMargin = Math.round(bounds.left) - getPaddingLeft();
            params.topMargin = Math.round(bounds.top) - getPaddingTop() - dp(2);
            topCard.setLayoutParams(params);
        }
        applyVisibility();
        if (availabilityChanged) {
            notifyOffsetChanged();
        }
    }

    private void updateMoveAccessibilityActions() {
        if (middleBounds.isEmpty() && topBounds.isEmpty()) {
            if (moveUpAccessibilityActionId != -1) {
                ViewCompat.removeAccessibilityAction(card, moveUpAccessibilityActionId);
                moveUpAccessibilityActionId = -1;
            }
        } else if (moveUpAccessibilityActionId == -1) {
            moveUpAccessibilityActionId = ViewCompat.addAccessibilityAction(
                    card,
                    getString(R.string.FluxGramMoveUp),
                    (view, arguments) -> {
                        settleTo(middleBounds.isEmpty()
                                ? AudioMiniPlayerPosition.TOP
                                : AudioMiniPlayerPosition.MIDDLE);
                        return true;
                    }
            );
        }

        if (topBounds.isEmpty()) {
            if (moveUpMiddleAccessibilityActionId != -1) {
                ViewCompat.removeAccessibilityAction(
                        middleCard,
                        moveUpMiddleAccessibilityActionId
                );
                moveUpMiddleAccessibilityActionId = -1;
            }
        } else if (moveUpMiddleAccessibilityActionId == -1) {
            moveUpMiddleAccessibilityActionId = ViewCompat.addAccessibilityAction(
                    middleCard,
                    getString(R.string.FluxGramMoveUp),
                    (view, arguments) -> {
                        settleTo(AudioMiniPlayerPosition.TOP);
                        return true;
                    }
            );
        }
    }

    public void setOffsetListener(Runnable listener) {
        offsetListener = listener;
    }

    public float getVisibleOffset(boolean chats) {
        float placement = chats ? getAvailableChatsPlacementProgress() : 0.0f;
        float liftedProgress = clamp(placement * 2.0f, 0.0f, 1.0f) * topZone;
        return dp(geometry.getHeightDp() + 8)
                * showProgress
                * hostFactor
                * (1.0f - liftedProgress);
    }

    public int getTargetOffset(boolean chats) {
        return shown
                && (!chats
                || targetPosition == AudioMiniPlayerPosition.BOTTOM
                || (targetPosition == AudioMiniPlayerPosition.MIDDLE && middleBounds.isEmpty())
                || (targetPosition == AudioMiniPlayerPosition.TOP && topBounds.isEmpty()))
                && hostFactor > 0.5f
                ? dp(geometry.getHeightDp() + 8)
                : 0;
    }

    public int getMiddleTargetOffset(boolean chats) {
        return chats
                && shown
                && targetPosition == AudioMiniPlayerPosition.MIDDLE
                && !middleBounds.isEmpty()
                && hostFactor > 0.5f
                ? Math.round(dp(80) * topVisibility)
                : 0;
    }

    public boolean isTopTarget(boolean chats) {
        return chats
                && shown
                && targetPosition == AudioMiniPlayerPosition.TOP
                && !topBounds.isEmpty();
    }

    private boolean onCardTouch(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (dismissAnimator != null) {
                return true;
            }

            dragging = false;
            dismissDragging = false;
            dismissDirection = DISMISS_NONE;
            touchActive = true;
            dragCard = view;
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
            touchActive = false;
            if (dismissDragging) {
                dismissDragging = false;
                animateDismiss(false);
                recycleVelocityTracker();
                return true;
            }
            boolean wasDragging = dragging;
            if (dragging) {
                dragging = false;
                settleTo(getNearestPosition(placementProgress));
            }
            recycleVelocityTracker();
            return wasDragging;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            lastRawY = event.getRawY();
            float deltaX = event.getRawX() - downRawX;
            float deltaY = lastRawY - downRawY;
            if (!dragging && !dismissDragging
                    && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                removeCallbacks(startDragging);
                int direction = resolveDismissDirection(view, deltaX, deltaY);
                if (direction != DISMISS_NONE) {
                    beginDismissGesture(view, direction);
                } else {
                    touchActive = false;
                }
            }

            if (dismissDragging) {
                updateDismissGesture(deltaX, deltaY);
                return true;
            }

            if (dragging) {
                updateDrag(lastRawY);
                return true;
            }

            return false;
        }

        removeCallbacks(startDragging);
        if (action == MotionEvent.ACTION_UP && dismissDragging) {
            touchActive = false;
            dismissDragging = false;
            float projectedVelocity = 0.0f;
            if (velocityTracker != null) {
                velocityTracker.computeCurrentVelocity(1000);
                projectedVelocity = getProjectedVelocity(
                        velocityTracker.getXVelocity(),
                        velocityTracker.getYVelocity()
                );
            }
            boolean dismiss = getDismissDistance() >= dp(72)
                    || (getDismissDistance() >= dp(12)
                    && projectedVelocity >= DISMISS_FLING_THRESHOLD);
            animateDismiss(dismiss);
            recycleVelocityTracker();
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL && dismissDragging) {
            touchActive = false;
            dismissDragging = false;
            animateDismiss(false);
            recycleVelocityTracker();
            return true;
        }

        if (action == MotionEvent.ACTION_UP && dragging) {
            touchActive = false;
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
            touchActive = false;
            settleTo(getNearestPosition(placementProgress));
            dragging = false;
            recycleVelocityTracker();
            return true;
        }

        touchActive = false;
        recycleVelocityTracker();
        return false;
    }

    private int resolveDismissDirection(View view, float deltaX, float deltaY) {
        if (positionSpring != null) {
            return DISMISS_NONE;
        }

        if (view == card) {
            return deltaY > 0.0f && Math.abs(deltaY) >= Math.abs(deltaX)
                    ? DISMISS_DOWN
                    : DISMISS_NONE;
        }

        if (view == middleCard) {
            if (Math.abs(deltaX) <= Math.abs(deltaY)) {
                return DISMISS_NONE;
            }
            return deltaX < 0.0f ? DISMISS_LEFT : DISMISS_RIGHT;
        }

        if (view != topCard) {
            return DISMISS_NONE;
        }

        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            return deltaX < 0.0f ? DISMISS_LEFT : DISMISS_RIGHT;
        }
        return deltaY < 0.0f ? DISMISS_UP : DISMISS_NONE;
    }

    private void beginDismissGesture(View view, int direction) {
        dismissDirection = direction;
        dismissDragging = true;
        touchActive = false;
        dragCard = view;
        ((DraggableCard) view).captureDrag();
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void updateDismissGesture(float deltaX, float deltaY) {
        if (dismissDirection == DISMISS_LEFT) {
            dismissOffsetX = Math.min(0.0f, deltaX);
            dismissOffsetY = 0.0f;
        } else if (dismissDirection == DISMISS_RIGHT) {
            dismissOffsetX = Math.max(0.0f, deltaX);
            dismissOffsetY = 0.0f;
        } else if (dismissDirection == DISMISS_UP) {
            dismissOffsetX = 0.0f;
            dismissOffsetY = Math.min(0.0f, deltaY);
        } else {
            dismissOffsetX = 0.0f;
            dismissOffsetY = Math.max(0.0f, deltaY);
        }

        float progress = clamp(getDismissDistance() / dp(120), 0.0f, 1.0f);
        dismissAlpha = 1.0f - 0.45f * smoothStep(progress);
        applyVisibility();
    }

    private float getDismissDistance() {
        return dismissDirection == DISMISS_LEFT || dismissDirection == DISMISS_RIGHT
                ? Math.abs(dismissOffsetX)
                : Math.abs(dismissOffsetY);
    }

    private float getProjectedVelocity(float velocityX, float velocityY) {
        if (dismissDirection == DISMISS_LEFT) {
            return -velocityX;
        }
        if (dismissDirection == DISMISS_RIGHT) {
            return velocityX;
        }
        if (dismissDirection == DISMISS_UP) {
            return -velocityY;
        }
        return velocityY;
    }

    private void animateDismiss(boolean dismiss) {
        if (dismissAnimator != null) {
            ValueAnimator animator = dismissAnimator;
            dismissAnimator = null;
            animator.cancel();
        }

        float startX = dismissOffsetX;
        float startY = dismissOffsetY;
        float startAlpha = dismissAlpha;
        float targetX = dismiss ? getDismissTargetX() : 0.0f;
        float targetY = dismiss ? getDismissTargetY() : 0.0f;
        float targetAlpha = dismiss ? 0.0f : 1.0f;
        if (!areAnimationsEnabled() || !isAttachedToWindow()) {
            dismissOffsetX = targetX;
            dismissOffsetY = targetY;
            dismissAlpha = targetAlpha;
            applyVisibility();
            finishDismissAnimation(dismiss);
            return;
        }

        dismissAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        dismissAnimator.setDuration(dismiss ? 180 : 220);
        dismissAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        dismissAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            dismissOffsetX = lerp(startX, targetX, progress);
            dismissOffsetY = lerp(startY, targetY, progress);
            dismissAlpha = lerp(startAlpha, targetAlpha, progress);
            applyVisibility();
        });
        dismissAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (dismissAnimator != animation) {
                    return;
                }

                dismissAnimator = null;
                finishDismissAnimation(dismiss);
            }
        });
        dismissAnimator.start();
    }

    private float getDismissTargetX() {
        if (dismissDirection != DISMISS_LEFT && dismissDirection != DISMISS_RIGHT) {
            return 0.0f;
        }

        View activeCard = dragCard == null ? getSettledCard() : dragCard;
        float baseX = activeCard.getX() - dismissOffsetX;
        return dismissDirection == DISMISS_LEFT
                ? -baseX - activeCard.getWidth() - dp(16)
                : getWidth() - baseX + dp(16);
    }

    private float getDismissTargetY() {
        if (dismissDirection != DISMISS_UP && dismissDirection != DISMISS_DOWN) {
            return 0.0f;
        }

        View activeCard = dragCard == null ? getSettledCard() : dragCard;
        float baseY = activeCard.getY() - dismissOffsetY;
        return dismissDirection == DISMISS_UP
                ? -baseY - activeCard.getHeight() - dp(16)
                : getHeight() - baseY + dp(16);
    }

    private void finishDismissAnimation(boolean dismiss) {
        dragCard = null;
        if (dismiss) {
            MediaController.getInstance().cleanupPlayer(true, true);
            return;
        }

        dismissDirection = DISMISS_NONE;
        dismissOffsetX = 0.0f;
        dismissOffsetY = 0.0f;
        dismissAlpha = 1.0f;
        applyVisibility();
    }

    private void resetDismissState() {
        if (dismissAnimator != null) {
            ValueAnimator animator = dismissAnimator;
            dismissAnimator = null;
            animator.cancel();
        }
        dismissDragging = false;
        dismissDirection = DISMISS_NONE;
        dismissOffsetX = 0.0f;
        dismissOffsetY = 0.0f;
        dismissAlpha = 1.0f;
    }

    private void startDragging() {
        if (!shown
                || dismissDragging
                || dragCard == null
                || !touchActive
                || hostFactor < 0.99f
                || topVisibility < 0.99f
                || topZone < 0.99f
                || (middleBounds.isEmpty() && topBounds.isEmpty())
                || hostBottom - getHighestAvailableTop() <= dp(48)) {
            return;
        }

        dragging = true;
        ((DraggableCard) dragCard).captureDrag();
        cancelPositionSpring();
        dragStartRawY = lastRawY;
        dragStartProgress = getDisplayPlacementProgress(true);
        setPlacementProgress(dragStartProgress);
        magnetTarget = getMagnetTarget(placementProgress);
        animateDragActivation();
        performDragHaptic();
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    private void updateDrag(float rawY) {
        float startY = getPositionY(dragStartProgress);
        float rawProgress = getProgressForY(startY + rawY - dragStartRawY);
        int nextMagnetTarget = getMagnetTarget(rawProgress);
        if (nextMagnetTarget != 0 && nextMagnetTarget != magnetTarget) {
            performDragHaptic();
        }
        magnetTarget = nextMagnetTarget;

        setPlacementProgress(applyMagnet(rawProgress));
    }

    private void performDragHaptic() {
        (dragCard == null ? card : dragCard).performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK
        );
    }

    private void settleAfterDrag(float velocityY) {
        AudioMiniPlayerPosition position;
        if (velocityY <= -FLING_THRESHOLD) {
            position = getAdjacentPosition(placementProgress, true);
        } else if (velocityY >= FLING_THRESHOLD) {
            position = getAdjacentPosition(placementProgress, false);
        } else {
            position = getNearestPosition(placementProgress);
        }

        dragging = false;
        settleTo(position);
    }

    private void settleTo(AudioMiniPlayerPosition position) {
        setDragLifted(false, true);
        dragCard = null;
        targetPosition = position;
        notifyOffsetChanged();
        float target = getPositionProgress(position);
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

    private void finishPositionImmediately(AudioMiniPlayerPosition position) {
        removeCallbacks(startDragging);
        dragging = false;
        touchActive = false;
        dragCard = null;
        cancelPositionSpring();
        recycleVelocityTracker();
        setDragLifted(false, false);
        setPlacementProgress(getPositionProgress(position));
        finishPositionSettle(position);
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

    private void interruptHiddenPlacement() {
        if (topVisibility >= 0.99f || (!dragging && positionSpring == null)) {
            return;
        }

        AudioMiniPlayerPosition interruptedPosition = dragging
                ? getNearestPosition(placementProgress)
                : targetPosition;
        finishPositionImmediately(interruptedPosition);
    }

    private float getDisplayPlacementProgress(boolean chats) {
        if (!chats || topZone < 0.5f) {
            return 0.0f;
        }
        return getAvailableChatsPlacementProgress();
    }

    private float getAvailableChatsPlacementProgress() {
        if (targetPosition == AudioMiniPlayerPosition.MIDDLE && middleBounds.isEmpty()) {
            return 0.0f;
        }
        if (placementProgress > 0.5f && topBounds.isEmpty()) {
            return 0.0f;
        }
        if (placementProgress > 0.0f && middleBounds.isEmpty() && topBounds.isEmpty()) {
            return 0.0f;
        }
        return placementProgress;
    }

    private static float getPositionProgress(AudioMiniPlayerPosition position) {
        if (position == AudioMiniPlayerPosition.TOP) {
            return 1.0f;
        }
        if (position == AudioMiniPlayerPosition.MIDDLE) {
            return 0.5f;
        }
        return 0.0f;
    }

    private AudioMiniPlayerPosition getNearestPosition(float progress) {
        if (!topBounds.isEmpty() && progress >= 0.75f) {
            return AudioMiniPlayerPosition.TOP;
        }
        if (!middleBounds.isEmpty() && progress >= 0.25f) {
            return AudioMiniPlayerPosition.MIDDLE;
        }
        return AudioMiniPlayerPosition.BOTTOM;
    }

    private AudioMiniPlayerPosition getAdjacentPosition(float progress, boolean upward) {
        if (upward) {
            if (!middleBounds.isEmpty() && progress < 0.5f - 0.001f) {
                return AudioMiniPlayerPosition.MIDDLE;
            }
            if (!topBounds.isEmpty()) {
                return AudioMiniPlayerPosition.TOP;
            }
            return middleBounds.isEmpty()
                    ? AudioMiniPlayerPosition.BOTTOM
                    : AudioMiniPlayerPosition.MIDDLE;
        }

        if (!middleBounds.isEmpty() && progress > 0.5f + 0.001f) {
            return AudioMiniPlayerPosition.MIDDLE;
        }
        return AudioMiniPlayerPosition.BOTTOM;
    }

    private float getHighestAvailableTop() {
        if (!topBounds.isEmpty()) {
            return topBounds.top;
        }
        if (!middleBounds.isEmpty()) {
            return middleBounds.top;
        }
        return hostBottom;
    }

    private float getPositionY(float progress) {
        if (middleBounds.isEmpty()) {
            if (topBounds.isEmpty()) {
                return hostBottom;
            }
            return lerp(hostBottom, topBounds.top, progress);
        }
        if (progress <= 0.5f || topBounds.isEmpty()) {
            return lerp(hostBottom, middleBounds.top, clamp(progress * 2.0f, 0.0f, 1.0f));
        }
        return lerp(middleBounds.top, topBounds.top, (progress - 0.5f) * 2.0f);
    }

    private float getProgressForY(float y) {
        if (middleBounds.isEmpty()) {
            float range = Math.max(1.0f, hostBottom - getHighestAvailableTop());
            return clamp((hostBottom - y) / range, 0.0f, topBounds.isEmpty() ? 0.0f : 1.0f);
        }
        if (y >= middleBounds.top || topBounds.isEmpty()) {
            float range = Math.max(1.0f, hostBottom - middleBounds.top);
            return clamp((hostBottom - y) / range * 0.5f, 0.0f, 0.5f);
        }

        float range = Math.max(1.0f, middleBounds.top - topBounds.top);
        return clamp(
                0.5f + (middleBounds.top - y) / range * 0.5f,
                0.5f,
                1.0f
        );
    }

    private int getMagnetTarget(float progress) {
        float zone = MAGNET_ZONE * 0.5f;
        if (progress <= zone) {
            return 1;
        }
        if (!middleBounds.isEmpty() && Math.abs(progress - 0.5f) <= zone) {
            return 2;
        }
        if (!topBounds.isEmpty() && progress >= 1.0f - zone) {
            return 3;
        }
        return 0;
    }

    private float applyMagnet(float progress) {
        float zone = MAGNET_ZONE * 0.5f;
        float anchor;
        int target = getMagnetTarget(progress);
        if (target == 1) {
            anchor = 0.0f;
        } else if (target == 2) {
            anchor = 0.5f;
        } else if (target == 3) {
            anchor = 1.0f;
        } else {
            return progress;
        }

        float delta = progress - anchor;
        float normalized = Math.abs(delta) / zone;
        return anchor + Math.signum(delta) * zone * normalized * normalized;
    }

    private View getSettledCard() {
        if (settledPosition == AudioMiniPlayerPosition.TOP) {
            return topCard;
        }
        if (settledPosition == AudioMiniPlayerPosition.MIDDLE) {
            return middleCard;
        }
        return card;
    }

    private void animateDragActivation() {
        View activatedCard = dragCard == null ? card : dragCard;
        activatedCard.getLocationOnScreen(cardLocation);
        float localX = clamp(downRawX - cardLocation[0], 0.0f, activatedCard.getWidth());
        float localY = clamp(downRawY - cardLocation[1], 0.0f, activatedCard.getHeight());
        activatedCard.setPressed(false);
        activatedCard.drawableHotspotChanged(localX, localY);
        activatedCard.setPressed(true);
        removeCallbacks(releaseActivationRipple);
        postDelayed(releaseActivationRipple, 90);

        setDragLifted(true, true);
        if (activatedCard == card
                && areAnimationsEnabled()
                && !LiteMode.isPowerSaverApplied()) {
            activatedCard.getLocationInWindow(cardLocation);
            LaunchActivity.makeRipple(
                    cardLocation[0] + activatedCard.getWidth() / 2.0f,
                    cardLocation[1] + activatedCard.getHeight() / 2.0f,
                    ACTIVATION_WAVE_INTENSITY
            );
        }
    }

    private void setDragLifted(boolean lifted, boolean animated) {
        card.animate().cancel();
        middleCard.animate().cancel();
        topCard.animate().cancel();
        float scale = lifted ? 1.02f : 1.0f;
        float translationZ = lifted ? dp(3) : 0.0f;
        if (!animated || !areAnimationsEnabled()) {
            setCardTransform(card, scale, translationZ);
            setCardTransform(middleCard, scale, translationZ);
            setCardTransform(topCard, scale, translationZ);
            return;
        }

        card.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(translationZ)
                .setDuration(lifted ? 160 : 220)
                .start();
        middleCard.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(translationZ)
                .setDuration(lifted ? 160 : 220)
                .start();
        topCard.animate()
                .scaleX(scale)
                .scaleY(scale)
                .translationZ(translationZ)
                .setDuration(lifted ? 160 : 220)
                .start();
    }

    private static void setCardTransform(View view, float scale, float translationZ) {
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setTranslationZ(translationZ);
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
        middleCardBackground.setColor(backgroundColor);
        topCardBackground.setColor(backgroundColor);
        morphingCard.setColor(backgroundColor);
        morphingCard.setControlColor(controlColor);
        titleView.setTextColor(titleColor);
        middleTitleView.setTextColor(titleColor);
        middleArtistView.setTextColor(ColorUtils.setAlphaComponent(artistColor, 0xcc));
        topTitleView.setTextColor(titleColor);
        this.artistColor = ColorUtils.setAlphaComponent(artistColor, 0xcc);
        bindText();
        previousButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        nextButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        middlePreviousButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        middleNextButton.setColorFilter(controlColor, PorterDuff.Mode.MULTIPLY);
        previousButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        nextButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        middlePreviousButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        middleNextButton.setBackground(Theme.createSelectorDrawable(
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
        middlePlayButton.setBackground(Theme.createSelectorDrawable(
                rippleColor,
                Theme.RIPPLE_MASK_CIRCLE_20DP
        ));
        middlePlayButton.setColors(
                controlColor,
                ColorUtils.setAlphaComponent(controlColor, 46)
        );
        topWaveView.setColors(
                controlColor,
                ColorUtils.setAlphaComponent(controlColor, 46)
        );
        topPlayButton.setColor(controlColor);
        topPlayButton.setBackground(null);
        card.setForeground(null);
        middleCard.setForeground(null);
        topCard.setForeground(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int shadowColor = ColorUtils.setAlphaComponent(Color.BLACK, 48);
            card.setOutlineAmbientShadowColor(shadowColor);
            card.setOutlineSpotShadowColor(shadowColor);
            middleCard.setOutlineAmbientShadowColor(shadowColor);
            middleCard.setOutlineSpotShadowColor(shadowColor);
            topCard.setOutlineAmbientShadowColor(shadowColor);
            topCard.setOutlineSpotShadowColor(shadowColor);
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
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                animationScaleObserver
        );
        LiteMode.addOnPowerSaverAppliedListener(powerSaverCallback);

        update(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        removeCallbacks(startDragging);
        removeCallbacks(releaseActivationRipple);
        card.setPressed(false);
        middleCard.setPressed(false);
        topCard.setPressed(false);
        AudioMiniPlayerPosition detachedTarget = targetPosition;
        boolean settlingPosition = positionSpring != null;
        cancelPositionSpring();
        recycleVelocityTracker();
        dragging = false;
        touchActive = false;
        resetDismissState();
        setDragLifted(false, false);
        dragCard = null;
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
            visibilityAnimator = null;
        }
        showProgress = shown ? 1.0f : 0.0f;
        if (settlingPosition) {
            setPlacementProgress(getPositionProgress(detachedTarget));
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
        getContext().getContentResolver().unregisterContentObserver(animationScaleObserver);
        LiteMode.removeOnPowerSaverAppliedListener(powerSaverCallback);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            playButton.progressChanged();
            middlePlayButton.progressChanged();
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
        boolean animateControls = animated
                && areAnimationsEnabled()
                && !LiteMode.isPowerSaverApplied();
        playButton.setPlaying(playing, animateControls);
        middlePlayButton.setPlaying(playing, animateControls);
        topWaveView.setPlaying(playing);
        morphingCard.setPlaying(playing);
        topPlayButton.setPlaying(playing, animateControls);
        playButton.setContentDescription(getString(
                playing ? R.string.AccActionPause : R.string.AccActionPlay
        ));
        middlePlayButton.setContentDescription(getString(
                playing ? R.string.AccActionPause : R.string.AccActionPlay
        ));
        topPlayButton.setContentDescription(getString(
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
        topCard.setContentDescription(card.getContentDescription());
        middleCard.setContentDescription(card.getContentDescription());
        playButton.setMessage(messageObject);
        middlePlayButton.setMessage(messageObject);

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
        middleTitleView.setText(titleText);
        middleArtistView.setText(artistText);
        topTitleView.setText(titleText);
    }

    private void updateCover(MessageObject messageObject) {
        coverView.getImageReceiver().setCurrentAccount(messageObject.currentAccount);
        middleCoverView.getImageReceiver().setCurrentAccount(messageObject.currentAccount);
        topCoverView.getImageReceiver().setCurrentAccount(messageObject.currentAccount);
        AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            middleCoverView.setImageBitmap(audioInfo.getCover());
            topCoverView.setImageBitmap(audioInfo.getCover());
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
        middleCoverView.setImage(
                artworkLocation,
                middleCoverFilter,
                thumbLocation,
                middleCoverFilter,
                getContext().getDrawable(R.drawable.nocover_small),
                messageObject
        );
        topCoverView.getImageReceiver().setCurrentAccount(messageObject.currentAccount);
        topCoverView.setImage(
                artworkLocation,
                "24_24",
                thumbLocation,
                "24_24",
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

        if (value && !shown) {
            resetDismissState();
        }
        shown = value;
        if (!value) {
            AudioMiniPlayerPosition hiddenPosition = targetPosition;
            if (dragging) {
                hiddenPosition = getNearestPosition(placementProgress);
            }
            finishPositionImmediately(hiddenPosition);
        }
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

        float displayPlacementProgress = getDisplayPlacementProgress(true);
        boolean lifted = displayPlacementProgress > 0.0f
                && (!middleBounds.isEmpty() || !topBounds.isEmpty());
        float swap = Math.min(1.0f, Math.abs(topZone - 0.5f) * 2.0f);
        float positionVisibility = displayPlacementProgress > 0.0f
                ? lerp(
                        hostFactor,
                        topVisibility,
                        clamp(displayPlacementProgress * 2.0f, 0.0f, 1.0f)
                )
                : hostFactor;
        float progress = showProgress * positionVisibility * swap;
        float position = lifted ? getPositionY(displayPlacementProgress) : hostBottom;
        float direction = lifted ? 1.0f - 2.0f * displayPlacementProgress : 1.0f;
        float bottomAlpha = 1.0f - smoothStep(0.32f, 0.48f, displayPlacementProgress);
        float middleAlpha = smoothStep(0.32f, 0.48f, displayPlacementProgress)
                * (1.0f - smoothStep(0.52f, 0.68f, displayPlacementProgress));
        float topAlpha = smoothStep(0.52f, 0.68f, displayPlacementProgress);
        boolean upperSegment = displayPlacementProgress > 0.5f;
        float segmentProgress = !upperSegment
                ? displayPlacementProgress * 2.0f
                : (displayPlacementProgress - 0.5f) * 2.0f;
        float morphAlpha = (float) Math.sin(Math.PI * segmentProgress);
        float showTranslation = dp(24) * (1.0f - progress) * direction;

        card.setTranslationX(dismissOffsetX);
        card.setTranslationY(position
                + showTranslation
                + dismissOffsetY);
        card.setAlpha(progress * bottomAlpha * dismissAlpha);
        cardBackground.setAlpha(255);
        coverView.setAlpha(1.0f);
        titleView.setAlpha(1.0f);
        previousButton.setAlpha(1.0f);
        playButton.setTransitionProgress(0.0f);
        playButton.setAlpha(1.0f);
        nextButton.setAlpha(1.0f);
        card.setVisibility(card.getAlpha() > 0.0f ? VISIBLE : INVISIBLE);

        middleCard.setTranslationX(dismissOffsetX);
        middleCard.setTranslationY(
                position
                        - middleCard.getTop()
                        + showTranslation
                        + dismissOffsetY
        );
        middleCard.setAlpha(progress * middleAlpha * dismissAlpha);
        middleCardBackground.setAlpha(255);
        middlePlayButton.setTransitionProgress(
                upperSegment ? smoothStep(segmentProgress) : 0.0f
        );
        middleCard.setVisibility(middleCard.getAlpha() > 0.0f ? VISIBLE : INVISIBLE);

        topCard.setTranslationX(dismissOffsetX);
        topCard.setTranslationY(
                position
                        - topCard.getTop()
                        + showTranslation
                        + dismissOffsetY
        );
        topCard.setAlpha(progress * topAlpha * dismissAlpha);
        topCardBackground.setAlpha(255);
        topCoverView.setAlpha(1.0f);
        topTitleView.setAlpha(1.0f);
        topWaveView.setAlpha(1.0f);
        topPlayButton.setAlpha(1.0f);
        topCard.setVisibility(topCard.getAlpha() > 0.0f ? VISIBLE : INVISIBLE);

        updateMorphingCard(
                segmentProgress,
                progress * morphAlpha * dismissAlpha,
                upperSegment
        );
        boolean interactionsEnabled = displayPlacementProgress < 0.5f
                || topVisibility >= 0.99f;
        card.setEnabled(interactionsEnabled);
        middleCard.setEnabled(interactionsEnabled);
        middlePlayButton.setEnabled(interactionsEnabled);
        topCard.setEnabled(interactionsEnabled);
        topPlayButton.setEnabled(interactionsEnabled);
        int hiddenAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        card.setImportantForAccessibility(
                interactionsEnabled && displayPlacementProgress < 0.4f
                        ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        : hiddenAccessibility
        );
        middleCard.setImportantForAccessibility(
                interactionsEnabled
                        && displayPlacementProgress >= 0.4f
                        && displayPlacementProgress < 0.6f
                        ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        : hiddenAccessibility
        );
        topCard.setImportantForAccessibility(
                interactionsEnabled && displayPlacementProgress >= 0.6f
                        ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        : hiddenAccessibility
        );
        setVisibility(progress > 0.0f ? VISIBLE : GONE);
    }

    private void updateMorphingCard(float progress, float alpha, boolean upperSegment) {
        if (alpha <= 0.0f
                || card.getWidth() <= 0
                || middleCard.getWidth() <= 0
                || topCard.getWidth() <= 0) {
            morphingCard.setVisibility(INVISIBLE);
            return;
        }

        View startCard = upperSegment ? middleCard : card;
        View endCard = upperSegment ? topCard : middleCard;
        float startWidth = startCard.getWidth();
        float startHeight = upperSegment ? dp(64) : dp(geometry.getHeightDp());
        float endWidth = endCard.getWidth();
        float endHeight = upperSegment ? dp(36) : dp(64);
        float startTop = upperSegment ? middleBounds.top : hostBottom;
        float endTop = upperSegment ? topCard.getTop() + dp(6) : middleBounds.top;
        float centerX = lerp(
                startCard.getLeft() + startWidth / 2.0f,
                endCard.getLeft() + endWidth / 2.0f,
                progress
        );
        float centerY = lerp(
                startTop + startHeight / 2.0f,
                endTop + endHeight / 2.0f,
                progress
        );
        float stretch = (float) Math.sin(Math.PI * progress);
        float width = lerp(startWidth, endWidth, progress) + dp(8) * stretch;
        float height = lerp(startHeight, endHeight, progress) + dp(12) * stretch;
        float radius = lerp(
                upperSegment ? dp(20) : dp(geometry.getCornerRadiusDp()),
                upperSegment ? dp(18) : dp(20),
                progress
        );

        morphingCard.setMorph(
                centerX,
                centerY,
                width,
                height,
                Math.min(radius, height / 2.0f),
                alpha,
                upperSegment ? progress : 0.0f
        );
    }

    private void notifyOffsetChanged() {
        if (offsetListener != null) {
            offsetListener.run();
        }
    }

    private static float smoothStep(float value) {
        float bounded = clamp(value, 0.0f, 1.0f);
        return bounded * bounded * (3.0f - 2.0f * bounded);
    }

    private static float smoothStep(float start, float end, float value) {
        return smoothStep((value - start) / (end - start));
    }

    private static final class MorphingCardView extends View {
        private static final float[] WAVE_HEIGHTS = {6.0f, 12.0f, 18.0f, 11.0f, 7.0f};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private float radius;
        private float progress;
        private boolean playing;

        MorphingCardView(Context context) {
            super(context);
            wavePaint.setStrokeCap(Paint.Cap.ROUND);
            wavePaint.setStrokeWidth(dp(2));
            setVisibility(INVISIBLE);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setControlColor(int color) {
            wavePaint.setColor(color);
            invalidate();
        }

        void setPlaying(boolean playing) {
            this.playing = playing;
            invalidate();
        }

        void setMorph(
                float centerX,
                float centerY,
                float width,
                float height,
                float radius,
                float alpha,
                float progress
        ) {
            bounds.set(
                    centerX - width / 2.0f,
                    centerY - height / 2.0f,
                    centerX + width / 2.0f,
                    centerY + height / 2.0f
            );
            this.radius = radius;
            this.progress = progress;
            setAlpha(alpha);
            setVisibility(VISIBLE);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            canvas.drawRoundRect(bounds, radius, radius, paint);

            float waveAlpha = smoothStep(0.18f, 0.54f, progress)
                    * (1.0f - smoothStep(0.96f, 1.0f, progress));
            if (waveAlpha <= 0.0f) {
                return;
            }

            wavePaint.setAlpha(Math.round(255.0f * waveAlpha));
            float centerX = bounds.right - dp(44);
            float centerY = bounds.centerY();
            float activity = playing ? 1.0f : 0.55f;
            for (int i = 0; i < WAVE_HEIGHTS.length; i++) {
                float x = centerX + dp((i - 2) * 4);
                float halfHeight = dp(WAVE_HEIGHTS[i] * activity) / 2.0f;
                canvas.drawLine(x, centerY - halfHeight, x, centerY + halfHeight, wavePaint);
            }
        }
    }

    private static final class PlaybackWaveView extends View {
        private static final long FRAME_DELAY_MS = 48;

        private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Runnable nextFrame = this::invalidate;
        private boolean playing;
        private float phase;
        private long lastFrameTime;

        PlaybackWaveView(Context context) {
            super(context);
            activePaint.setStrokeCap(Paint.Cap.ROUND);
            inactivePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setColors(int active, int inactive) {
            activePaint.setColor(active);
            inactivePaint.setColor(inactive);
            invalidate();
        }

        void setPlaying(boolean value) {
            if (playing == value) {
                return;
            }

            playing = value;
            lastFrameTime = 0;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(nextFrame);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            long now = SystemClock.elapsedRealtime();
            if (lastFrameTime != 0 && playing) {
                phase = (phase + Math.min(80, now - lastFrameTime) * 0.012f) % TWO_PI;
            }
            lastFrameTime = now;

            float centerY = getHeight() / 2.0f;
            float spacing = getWidth() / 6.0f;
            float stroke = dpf2(2.2f);
            activePaint.setStrokeWidth(stroke);
            inactivePaint.setStrokeWidth(stroke);
            boolean animated = playing
                    && areAnimationsEnabled()
                    && !LiteMode.isPowerSaverApplied();
            for (int index = 0; index < 5; index++) {
                float x = spacing * (index + 1);
                float normalized = animated
                        ? 0.35f + 0.65f * Math.abs((float) Math.sin(phase + index * 0.82f))
                        : 0.35f + 0.15f * (index % 2);
                float halfHeight = dpf2(6) * normalized;
                canvas.drawLine(
                        x,
                        centerY - halfHeight,
                        x,
                        centerY + halfHeight,
                        animated ? activePaint : inactivePaint
                );
            }

            removeCallbacks(nextFrame);
            if (animated) {
                postDelayed(nextFrame, FRAME_DELAY_MS);
            } else {
                lastFrameTime = 0;
            }
        }
    }

    private final class DraggableCard extends LinearLayout {
        private boolean dragCaptured;

        private DraggableCard(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            boolean handled = onCardTouch(this, event);
            if (handled || dragCaptured) {
                if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_POINTER_DOWN
                        || action == MotionEvent.ACTION_POINTER_UP) {
                    dragCaptured = false;
                }
                return true;
            }

            boolean result = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragCaptured = false;
            }
            return result;
        }

        private void captureDrag() {
            if (dragCaptured) {
                return;
            }

            dragCaptured = true;
            long now = SystemClock.uptimeMillis();
            MotionEvent cancelEvent = MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_CANCEL,
                    0.0f,
                    0.0f,
                    0
            );
            super.dispatchTouchEvent(cancelEvent);
            cancelEvent.recycle();
        }
    }

    private static final class PlaybackButtonView extends View {
        private final PlayPauseDrawable drawable = new PlayPauseDrawable(14);

        PlaybackButtonView(Context context) {
            super(context);
            drawable.setParent(this);
        }

        void setColor(int color) {
            drawable.setColor(color);
            invalidate();
        }

        void setPlaying(boolean playing, boolean animated) {
            drawable.setPause(playing, animated);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            int half = dp(7);
            int centerX = getWidth() / 2;
            int centerY = getHeight() / 2;
            drawable.setBounds(centerX - half, centerY - half, centerX + half, centerY + half);
            drawable.draw(canvas);
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
        private float transitionProgress;

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

        void setTransitionProgress(float progress) {
            if (transitionProgress == progress) {
                return;
            }

            transitionProgress = progress;
            invalidate();
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

            float targetAmplitude = playing
                    && areAnimationsEnabled()
                    && !LiteMode.isPowerSaverApplied()
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
            float ringAlpha = 1.0f - smoothStep(0.16f, 0.56f, transitionProgress);
            int trackAlpha = trackPaint.getAlpha();
            int progressAlpha = progressPaint.getAlpha();
            trackPaint.setAlpha(Math.round(trackAlpha * ringAlpha));
            progressPaint.setAlpha(Math.round(progressAlpha * ringAlpha));
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
            trackPaint.setAlpha(trackAlpha);
            progressPaint.setAlpha(progressAlpha);

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
