package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import org.telegram.messenger.FileLog;
import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.fluxgram.AppearanceSettings;
import org.telegram.messenger.fluxgram.AudioMiniPlayerSettings;
import org.telegram.messenger.fluxgram.BottomNavigationSettings;
import org.telegram.messenger.fluxgram.FolderSettings;
import org.telegram.messenger.fluxgram.MaterialVisualStyleRuntime;
import org.telegram.messenger.fluxgram.ProfileDisplaySettings;
import org.telegram.messenger.fluxgram.RootNavigationPolicy;
import org.telegram.messenger.fluxgram.RootNavigationSettings;
import org.telegram.messenger.fluxgram.RootNavigationSnapshot;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.ActiveIndicatorGeometry;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MiniPlayerGeometry;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.GlassEngine;
import org.telegram.utils.glass.GlassSettings;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.fluxgram.FolderPresentation;
import org.telegram.ui.fluxgram.FolderUnreadCounter;
import org.telegram.ui.fluxgram.MaterialAudioMiniPlayer;
import org.telegram.ui.fluxgram.MaterialNavigationStyle;
import org.telegram.ui.fluxgram.MaterialProgressStyle;
import org.telegram.ui.Stories.recorder.HintView2;
import org.telegram.ui.Wallet.WalletActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {

    private static final int INDEX_CHATS = 0;
    private static final int INDEX_CONTACTS = 1;
    private static final int INDEX_CALLS = 2;
    private static final int INDEX_SETTINGS = 3;
    private static final int INDEX_PROFILE = 4;

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);


    private IUpdateLayout updateLayout;
    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private DockedNavigationDrawable dockedNavigationBackground;
    private View fadeView;
    private MaterialAudioMiniPlayer audioMiniPlayer;
    private final RectF audioMiniPlayerTopBounds = new RectF();
    private final RectF audioMiniPlayerResolvedTopBounds = new RectF();
    private final RectF audioMiniPlayerFragmentBounds = new RectF();
    private boolean audioMiniPlayerHasTopBounds;
    private final ViewTreeObserver.OnPreDrawListener audioMiniPlayerAnchorListener = () -> {
        updateAudioMiniPlayerTopAnchor();
        return true;
    };
    private final int bottomNavigationMode;
    private final boolean materialNavigationDocked;
    private final MiniPlayerGeometry audioMiniPlayerGeometry;
    private final RootNavigationPolicy.State rootNavigationState;

    public MainTabsActivity() {
        super();
        bottomNavigationMode = BottomNavigationSettings.normalize(SharedConfig.bottomNavigationMode);
        RootNavigationPolicy.State savedRootNavigationState = RootNavigationSettings.getState(
                getUserConfig().showCallsTab
        );
        rootNavigationState = BottomNavigationSettings.isVisible(bottomNavigationMode)
                ? savedRootNavigationState
                : RootNavigationPolicy.normalize(Collections.singletonList(
                        RootNavigationPolicy.CHATS));
        materialNavigationDocked = MaterialNavigationStyle.usesDockedContainer(
                MaterialVisualStyleRuntime.getSnapshot());
        audioMiniPlayerGeometry = MaterialProgressStyle.miniPlayerGeometry(
                MaterialVisualStyleRuntime.getSnapshot());
        if (!materialNavigationDocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = createTabSource(false);
            iBlur3SourceTabFrosted = createTabSource(true);
        } else {
            iBlur3SourceTabGlass = null;
            iBlur3SourceTabFrosted = null;
        }

        iBlur3SourceColor = new GlassSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                int tabsOffset = hasBottomNavigation()
                        ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN)
                        : 0;

                return navigationBarHeight + tabsOffset;
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    private static final class DockedNavigationDrawable extends Drawable {
        private final Paint backgroundPaint = new Paint();
        private final Paint dividerPaint = new Paint();
        private int backgroundColor;
        private int dividerColor;
        private int alpha = 255;

        public void setColors(int backgroundColor, int dividerColor) {
            if (this.backgroundColor == backgroundColor && this.dividerColor == dividerColor) {
                return;
            }

            this.backgroundColor = backgroundColor;
            this.dividerColor = dividerColor;
            updatePaints();
            invalidateSelf();
        }

        private void updatePaints() {
            float alphaFactor = alpha / 255.0f;
            backgroundPaint.setColor(Theme.multAlpha(backgroundColor, alphaFactor));
            dividerPaint.setColor(Theme.multAlpha(dividerColor, alphaFactor));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawRect(getBounds(), backgroundPaint);
            canvas.drawRect(
                    getBounds().left,
                    getBounds().top,
                    getBounds().right,
                    getBounds().top + Math.max(1.0f, AndroidUtilities.dpf2(0.5f)),
                    dividerPaint
            );
        }

        @Override
        public void setAlpha(int alpha) {
            int boundedAlpha = MathUtils.clamp(alpha, 0, 255);
            if (this.alpha != boundedAlpha) {
                this.alpha = boundedAlpha;
                updatePaints();
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            backgroundPaint.setColorFilter(colorFilter);
            dividerPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The pages under the tabs: an intermediate node over one or two of them, their glass or their frosted glass. */
    @RequiresApi(api = Build.VERSION_CODES.S)
    private GlassSourceRenderNode createTabSource(boolean frosted) {
        final GlassSourceRenderNode source = new GlassSourceRenderNode(null);
        source.setupRenderer(new RenderNodeWithHash.Renderer() {
            @Override
            public void renderNodeCalculateHash(IBlur3Hash hash) {
                hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                hash.add(SharedConfig.chatBlurEnabled());

                for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                    final FragmentState state = fragmentsArr.valueAt(a);
                    final BaseFragment fragment = state.fragment;
                    if (fragment.fragmentView == null) {
                        continue;
                    }
                    if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                        continue;
                    }
                    if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                        continue;
                    }

                    if (fragment instanceof TabFragmentDelegate) {
                        TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                        final GlassSourceRenderNode pageSource = frosted ? delegate.getFrostedGlassSource() : delegate.getGlassSource();
                        if (pageSource != null) {
                            hash.addF(fragmentPosition.left);
                            hash.addF(fragmentPosition.top);
                            hash.add(fragment.getClassGuid());
                        }
                    }
                }
            }

            @Override
            public void renderNodeUpdateDisplayList(Canvas canvas) {
                final int width = fragmentView.getMeasuredWidth();
                final int height = fragmentView.getMeasuredHeight();

                canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                    final FragmentState state = fragmentsArr.valueAt(a);
                    final BaseFragment fragment = state.fragment;
                    if (fragment.fragmentView == null) {
                        continue;
                    }
                    if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                        continue;
                    }
                    if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                        continue;
                    }

                    if (fragment instanceof TabFragmentDelegate) {
                        TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                        final GlassSourceRenderNode pageSource = frosted ? delegate.getFrostedGlassSource() : delegate.getGlassSource();
                        if (pageSource != null) {
                            canvas.save();
                            canvas.translate(fragmentPosition.left, fragmentPosition.top);
                            pageSource.draw(canvas, 0, 0, width, height);
                            canvas.restore();
                        }
                    }
                }
            }
        });
        return source;
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_audioMiniPlayerPosition();
                checkUi_fadeView();
                if (!materialNavigationDocked) {
                    blur3_updateFragmentsGlass();
                }
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                if (!materialNavigationDocked) {
                    blur3_invalidateBlur();
                }
                blur3_updateFadeColors();
            }
        };
    }

    private int getEstBackgroundColor() {
        int chatsPosition = positionForDestination(RootNavigationPolicy.CHATS);
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                viewPager != null ? viewPager.getPositionVisibility(chatsPosition) : 1);
    }

    private boolean tabletLayout;
    public void updateLayout() {
//        if (tabletLayout == AndroidUtilities.isTablet()) return;
//        tabletLayout = AndroidUtilities.isTablet();
//
//        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
//        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
//        int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
//        if (tabletLayout) {
//            bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
//        }
//        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL);
//        if (tabletLayout) {
//            lp.leftMargin = dp(6);
//            lp.rightMargin = dp(6);
//            lp.topMargin = dp(6);
//        }
//        lp.bottomMargin = bottomMargin;
//
//        viewPager.setLayoutParams(lp);
//        viewPager.setTabletLayout(tabletLayout);
//        checkUi_fadeView();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    private void checkContactsTabBadge() {
        GlassTabView contactsTab = getTab(RootNavigationPolicy.CONTACTS);
        if (tabsView != null && contactsTab != null) {
            final boolean hasPermission = Build.VERSION.SDK_INT >= 23 && ContactsController.hasContactsPermission();
            if (hasPermission) {
                MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts2", true).apply();
            }
            if (Build.VERSION.SDK_INT >= 23 && UserConfig.getInstance(currentAccount).syncContacts && !hasPermission && MessagesController.getGlobalNotificationsSettings().getBoolean("askAboutContacts2", true)) {
                contactsTab.setCounter("!", true, true);
            } else {
                contactsTab.setCounter(null, true, true);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;

        if (!hasBottomNavigation()) {
            iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

            fadeView = new View(context);
            fadeView.setVisibility(View.GONE);
            contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

            tabsViewWrapper = new FrameLayout(context);
            tabsViewWrapper.setVisibility(View.GONE);
            contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

            setupAudioMiniPlayer(context);
            setupUpdateLayout(context);
            return contentView;
        }

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setClipChildren(false);
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        tabsView.setMaxWidth(dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));

        tabs = new GlassTabView[RootNavigationPolicy.knownDestinations().size()];
        for (int position = 0; position < rootNavigationState.size(); position++) {
            String destination = rootNavigationState.getOrder().get(position);
            GlassTabView view = createTab(context, destination);
            tabs[itemKeyForDestination(destination)] = view;

            final int tabPosition = position;
            view.setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) {
                    return;
                }

                if (viewPager.getCurrentPosition() == tabPosition) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) {
                        ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    }
                    return;
                }

                selectTab(tabPosition, true);
                viewPager.scrollToPosition(tabPosition);
            });

            tabsView.addView(view);
            tabsView.setViewVisible(view, true, false);
        }
        ActiveIndicatorGeometry activeIndicatorGeometry =
                MaterialNavigationStyle.activeIndicatorGeometry(
                        MaterialVisualStyleRuntime.getSnapshot());
        tabsView.setActiveIndicatorGeometry(activeIndicatorGeometry);
        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        fadeView = new View(context);
        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});

        if (materialNavigationDocked) {
            dockedNavigationBackground = new DockedNavigationDrawable();
            dockedNavigationBackground.setColors(
                    getThemedColor(Theme.key_windowBackgroundWhite),
                    getThemedColor(Theme.key_divider)
            );
            tabsViewWrapper.setBackground(dockedNavigationBackground);
        } else {
            final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);
            BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass =
                    new BlurredBackgroundDrawableViewFactory(
                            iBlur3SourceTabGlass != null
                                    ? iBlur3SourceTabGlass
                                    : iBlur3SourceColor,
                            iBlur3SourceTabFrosted != null
                                    ? iBlur3SourceTabFrosted
                                    : iBlur3SourceColor,
                            iBlur3SourceColor
                    );
            iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
            iBlur3FactoryGlass.setLiquidGlassEffectAllowed(true);

            tabsViewBackground = iBlur3FactoryGlass.create(
                    tabsView,
                    BlurredBackgroundProviderImpl.mainTabs(resourceProvider)
            );
            tabsViewBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
            tabsViewBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
            tabsViewBackground.setOutlineStyle(
                    AppearanceSettings.getGlassOutlineStyle(currentAccount));
            tabsView.setBackground(tabsViewBackground);

            BlurredBackgroundDrawableViewFactory iBlur3FactoryFade =
                    new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
            iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);
            BlurredBackgroundWithFadeDrawable fadeDrawable =
                    new BlurredBackgroundWithFadeDrawable(
                            iBlur3FactoryFade.create(fadeView, null));
            fadeDrawable.setFadeHeight(dp(60), true);
            fadeView.setBackground(fadeDrawable);
        }

        tabsViewWrapper.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        tabsViewWrapper.setClipToPadding(false);
        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        setupAudioMiniPlayer(context);
        setupUpdateLayout(context);
        checkUnreadCount(false);
        return contentView;
    }

    private void setupAudioMiniPlayer(Context context) {
        if (audioMiniPlayerGeometry == null) {
            return;
        }

        audioMiniPlayer = new MaterialAudioMiniPlayer(
                context,
                resourceProvider,
                audioMiniPlayerGeometry,
                () -> {
                    if (getParentActivity() != null) {
                        new AudioPlayerAlert(getParentActivity(), resourceProvider).show();
                    }
                },
                AudioMiniPlayerSettings.getPosition(),
                AudioMiniPlayerSettings::setPosition
        );
        audioMiniPlayer.setOffsetListener(this::updateAudioMiniPlayerInsets);
        contentView.addView(audioMiniPlayer, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        contentView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(@NonNull View view) {
                view.getViewTreeObserver().addOnPreDrawListener(audioMiniPlayerAnchorListener);
            }

            @Override
            public void onViewDetachedFromWindow(@NonNull View view) {
                view.getViewTreeObserver().removeOnPreDrawListener(audioMiniPlayerAnchorListener);
            }
        });
    }

    private GlassTabView createTab(Context context, String destination) {
        GlassTabView tab;
        if (RootNavigationPolicy.CONTACTS.equals(destination)) {
            tab = GlassTabView.createMainTab(
                    context,
                    resourceProvider,
                    GlassTabView.TabAnimation.CONTACTS,
                    R.string.MainTabsContacts
            );
            tab.setOnLongClickListener(this::openContactsSelector);
        } else if (RootNavigationPolicy.CALLS.equals(destination)) {
            tab = GlassTabView.createMainTab(
                    context,
                    resourceProvider,
                    GlassTabView.TabAnimation.CALLS,
                    R.string.MainTabsCalls
            );
            tab.setOnLongClickListener(this::openCallsSelector);
        } else if (RootNavigationPolicy.SETTINGS.equals(destination)) {
            tab = GlassTabView.createMainTab(
                    context,
                    resourceProvider,
                    GlassTabView.TabAnimation.SETTINGS,
                    R.string.Settings
            );
        } else if (RootNavigationPolicy.PROFILE.equals(destination)) {
            tab = GlassTabView.createAvatar(
                    context,
                    resourceProvider,
                    currentAccount,
                    R.string.MainTabsProfile
            );
            tab.setOnLongClickListener(this::openAccountSelector);
        } else {
            tab = GlassTabView.createMainTab(
                    context,
                    resourceProvider,
                    GlassTabView.TabAnimation.CHATS,
                    R.string.MainTabsChats
            );
            tab.setOnLongClickListener(this::openFoldersSelector);
        }

        if (!RootNavigationPolicy.SETTINGS.equals(destination)) {
            tabsView.addTabToIgnoreClick(tab);
        }

        return tab;
    }

    private GlassTabView getTab(String destination) {
        if (tabs == null) {
            return null;
        }

        int itemKey = itemKeyForDestination(destination);
        return itemKey >= 0 && itemKey < tabs.length ? tabs[itemKey] : null;
    }

    private static int itemKeyForDestination(String destination) {
        if (RootNavigationPolicy.CONTACTS.equals(destination)) {
            return INDEX_CONTACTS;
        }
        if (RootNavigationPolicy.CALLS.equals(destination)) {
            return INDEX_CALLS;
        }
        if (RootNavigationPolicy.SETTINGS.equals(destination)) {
            return INDEX_SETTINGS;
        }
        if (RootNavigationPolicy.PROFILE.equals(destination)) {
            return INDEX_PROFILE;
        }

        return INDEX_CHATS;
    }

    private void setupUpdateLayout(Context context) {
        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null) {
            return;
        }

        GlassTabView chatsTab = getTab(RootNavigationPolicy.CHATS);
        if (chatsTab == null) {
            return;
        }

        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            chatsTab.setCounter(unreadCountFmt, false, animated);
        } else {
            chatsTab.setCounter(null, false, animated);
        }
    }

    public boolean openContactsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_contact_add, getString(R.string.NewContact), () -> {
            new NewContactBottomSheet(this, getContext()).show();
        });
        o.add(R.drawable.msg_calls, getString(R.string.VoipChatRecentCalls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            presentFragment(new CallLogActivity(args));
        });
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private Integer pendingFolderId;

    private boolean openFoldersSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters == null || filters.size() <= 1) return false;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        final FolderSettings.Policy folderPolicy = FolderSettings.getPolicy(currentAccount);
        final boolean showAllChats = FolderSettings.isAllChatsVisible(folderPolicy, filters);
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter folder = filters.get(i);
            if (folder.isDefault() && !showAllChats) {
                continue;
            }

            final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            folderItem.setPadding(dp(18), 0, dp(18), 0);
            CharSequence accessibleTitle = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
            CharSequence title = folderPolicy.getPresentationMode() == FolderSettings.MODE_ICON
                    ? ""
                    : accessibleTitle;
            title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
            if (!folder.isDefault()) {
                title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
            }
            final int unreadCount = FolderUnreadCounter.resolve(currentAccount, folder, folderPolicy);
            if (unreadCount > 0) {
                final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                final int counterStart = titleWithCounter.length();
                titleWithCounter.append(String.valueOf(unreadCount));
                titleWithCounter.setSpan(
                        new FolderCounterSpan(
                                unreadCount,
                                folderPolicy.getCounterMode() == FolderSettings.COUNTER_UNMUTED
                                        || hasUnmutedUnreadDialogs(folder)
                        ),
                        counterStart,
                        titleWithCounter.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                title = titleWithCounter;
            }
            folderItem.setContentDescription(unreadCount > 0
                    ? TextUtils.concat(
                            accessibleTitle,
                            "\n",
                            LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                    )
                    : accessibleTitle);
            folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            final int color = getMessagesController().folderTags ? folder.color : -1;
            FolderDrawable icon = folderPolicy.getPresentationMode() == FolderSettings.MODE_TITLE
                    ? null
                    : new FolderDrawable(getContext(), FolderPresentation.resolveIcon(folder), color);
            folderItem.setTextAndIcon(title, 0, icon);
            folderItem.getTextView().setEmojiColor(getThemedColor(Theme.key_featuredStickers_addButton));
            folderItem.setMinimumWidth(160);
            folderItem.setOnClickListener(e -> {
                o.dismiss();
                openFolder(folder.id);
            });
            o.addView(folderItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
//        o.setBlur(true);
        o.translate(-dp(8), -dp(4));
        o.setMaxHeight(dp(400));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.setGravity(Gravity.LEFT);
        o.show();

        return true;
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final MessagesController messagesController = getMessagesController();
        final ArrayList<TLRPC.Dialog> dialogs = folder.isDefault()
                ? messagesController.getDialogs(0)
                : messagesController.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!folder.isDefault()) {
                long dialogId = dialog.id;
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    final TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!folder.includesDialog(getAccountInstance(), dialogId, dialog)) {
                    continue;
                }
            }
            if ((messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark)
                    && !messagesController.isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    private class FolderCounterSpan extends ReplacementSpan {

        private static final float HEIGHT_DP = 17.333f;
        private final String count;
        private final boolean hasUnmutedUnreadDialogs;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float counterWidth;

        FolderCounterSpan(int count, boolean hasUnmutedUnreadDialogs) {
            this.count = String.valueOf(count);
            this.hasUnmutedUnreadDialogs = hasUnmutedUnreadDialogs;
            textPaint.setTextSize(AndroidUtilities.dpf2(11));
            textPaint.setTypeface(AndroidUtilities.bold());
            counterWidth = Math.max(dp(HEIGHT_DP - 10), textPaint.measureText(this.count)) + dp(10);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) Math.ceil(dp(5) + counterWidth);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float left = x + dp(5);
            final float centerY = (top + bottom) / 2f + dp(1);
            final float halfHeight = dp(HEIGHT_DP) / 2f;
            backgroundPaint.setColor(getThemedColor(
                hasUnmutedUnreadDialogs ?
                    Theme.key_featuredStickers_addButton :
                    Theme.key_chats_tabUnreadUnactiveBackground
            ));
            textPaint.setColor(getThemedColor(Theme.key_actionBarDefault));
            AndroidUtilities.rectTmp.set(left, centerY - halfHeight, left + counterWidth, centerY + halfHeight);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfHeight, halfHeight, backgroundPaint);
            final Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            final float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(count, left + (counterWidth - textPaint.measureText(count)) / 2f, baseline, textPaint);
        }
    }

    private void openFolder(int folderId) {
        int chatsPosition = positionForDestination(RootNavigationPolicy.CHATS);
        if (viewPager.getCurrentPosition() == chatsPosition && dialogsActivity != null) {
            dialogsActivity.scrollToFolder(folderId);
        } else {
            if (dialogsActivity == null) {
                prepareDialogsActivity(null);
            }
            pendingFolderId = folderId;
            selectTab(chatsPosition, true);
            viewPager.scrollToPosition(chatsPosition);
        }
    }

    public boolean openAccountSelector(View button) {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();

        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            if (l1 > l2) {
                return 1;
            } else if (l1 < l2) {
                return -1;
            }
            return 0;
        });

        ItemOptions o = ItemOptions.makeOptions(this, button);
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_addbot, getString(R.string.AddAccount), () -> {
                int freeAccounts = 0;
                Integer availableAccount = null;
                for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        freeAccounts++;
                        if (availableAccount == null) {
                            availableAccount = a;
                        }
                    }
                }
                if (!UserConfig.hasPremiumOnAccounts()) {
                    freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
                }
                if (freeAccounts > 0 && availableAccount != null) {
                    presentFragment(new LoginActivity(availableAccount));
                } else if (!UserConfig.hasPremiumOnAccounts()) {
                    showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
                }
            });
        }

        if (BuildConfig.DEBUG_PRIVATE_VERSION) {
            o.add(R.drawable.menu_download_round, "Dump Canvas", () -> AndroidUtilities.runOnUIThread(this::dumpCanvas, 1000));
        }

        if (accountNumbers.size() > 0) {
            if (o.getItemsCount() > 0) o.addGap();
            for (int acc : accountNumbers) {
                final int account = acc;
                final View btn = accountView(acc, currentAccount == acc);
                btn.setOnClickListener(v -> {
                    if (currentAccount == account) return;
                    o.dismiss();
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                });
                o.addView(btn, LayoutHelper.createLinear(230, 48));
            }
        }

        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();

        HintsController.Hint.AccountSwitchHint.doNotShowAgain();

        return true;
    }

    public LinearLayout accountView(int account, boolean selected) {
        final LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.HORIZONTAL);
        ThemeBind.selectorBackground(btn, Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 0, 0), null, ThemeBind.of(Theme.key_listSelector), getResourceProvider());

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        final FrameLayout avatarContainer = new FrameLayout(getContext()) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.33f));
                    selectedPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        btn.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(getContext());
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(dp(16));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER, 1, 1, 1, 1));

        final TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        ThemeBind.textColor(textView, Theme.key_dialogTextBlack, getResourceProvider());
        textView.setText(ProfileDisplaySettings.hidesOwnPhone()
                ? ProfileDisplaySettings.getNameWithoutPhone(user)
                : UserObject.getUserName(user));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));

        return btn;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        if (tabsView != null) {
            selectTab(viewPager.getCurrentPosition(), true);
            setGestureSelectedOverride(0, false);
        }
        blur3_invalidateBlur();

        if (viewPager != null) {
            final int currentPosition = viewPager.getCurrentPosition();
            if (pendingFolderId != null
                    && currentPosition == positionForDestination(RootNavigationPolicy.CHATS)
                    && dialogsActivity != null) {
                dialogsActivity.scrollToFolder(pendingFolderId);
                pendingFolderId = null;
            }
        }

    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                selectTab(Math.round(position), true);
            }
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }

    @Override
    protected int getFragmentsCount() {
        return rootNavigationState.size();
    }

    @Override
    protected int getStartPosition() {
        return positionForDestination(RootNavigationPolicy.CHATS);
    }

    @Override
    protected int getItemKey(int position) {
        return itemKeyForDestination(destinationForPosition(position));
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putBoolean("hasMainTabs", hasBottomNavigation());
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        prepareTabFragment(dialogsActivity);
        putFragmentAtPosition(positionForDestination(RootNavigationPolicy.CHATS), dialogsActivity);
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        String destination = destinationForPosition(position);
        if (RootNavigationPolicy.CONTACTS.equals(destination)) {
            Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", hasBottomNavigation());
            return prepareTabFragment(new ContactsActivity(args));
        } else if (RootNavigationPolicy.CALLS.equals(destination)) {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", hasBottomNavigation());
            args.putBoolean("hasRootAudioMiniPlayer", audioMiniPlayerGeometry != null);
            return prepareTabFragment(new CallLogActivity(args));
        } else if (RootNavigationPolicy.SETTINGS.equals(destination)) {
            Bundle args = new Bundle();
            args.putBoolean("hasMainTabs", hasBottomNavigation());
            return prepareTabFragment(new SettingsActivity(args));
        } else if (RootNavigationPolicy.CHATS.equals(destination)) {
            Bundle args = new Bundle();
            args.putBoolean("hasMainTabs", hasBottomNavigation());
            dialogsActivity = new DialogsActivity(args);
            dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
            return prepareTabFragment(dialogsActivity);
        } else if (RootNavigationPolicy.PROFILE.equals(destination)) {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            args.putBoolean("my_profile", true);
            // args.putBoolean("expandPhoto", true);
            args.putBoolean("hasMainTabs", hasBottomNavigation());
            return prepareTabFragment(new ProfileActivity(args));
        }
        return null;
    }

    private <T extends BaseFragment> T prepareTabFragment(T fragment) {
        if (fragment instanceof TabFragmentDelegate && audioMiniPlayer != null) {
            applyAudioMiniPlayerInset(
                    (TabFragmentDelegate) fragment,
                    fragment instanceof DialogsActivity
            );
        }
        return fragment;
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    private RootNavigationSnapshot getRootNavigationSnapshot() {
        if (fragmentView == null
                || !fragmentView.isAttachedToWindow()
                || viewPager == null
                || getParentLayout() == null) {
            return null;
        }

        int position = viewPager.getCurrentPosition();

        return RootNavigationSnapshot.create(
                currentAccount,
                getClassGuid(),
                bottomNavigationMode,
                rootNavigationState,
                destinationForPosition(position)
        );
    }

    private String destinationForPosition(int position) {
        if (position < 0 || position >= rootNavigationState.size()) {
            return RootNavigationPolicy.CHATS;
        }

        return rootNavigationState.getOrder().get(position);
    }

    private boolean navigateToRootDestination(
            RootNavigationSnapshot snapshot,
            String destination
    ) {
        RootNavigationSnapshot currentSnapshot = getRootNavigationSnapshot();
        if (currentSnapshot == null
                || snapshot == null
                || !currentSnapshot.matches(snapshot)
                || !RootNavigationSnapshot.isKnownDestination(destination)) {
            return false;
        }

        if (currentSnapshot.canSelectInRoot(destination)) {
            if (viewPager == null) {
                return false;
            }

            viewPager.scrollToPosition(positionForDestination(destination));

            return true;
        }

        BaseFragment fragment = createStandaloneDestination(destination);
        if (fragment == null
                || getParentLayout() == null
                || getParentLayout().checkTransitionAnimation()) {
            return false;
        }

        boolean presented = presentFragment(fragment);

        return presented || fragment.getParentLayout() != null;
    }

    private int positionForDestination(String destination) {
        int position = rootNavigationState.positionOf(destination);
        return position >= 0
                ? position
                : rootNavigationState.positionOf(RootNavigationPolicy.CHATS);
    }

    private BaseFragment createStandaloneDestination(String destination) {
        Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", false);

        if (RootNavigationSnapshot.CONTACTS.equals(destination)) {
            args.putBoolean("needPhonebook", true);
            args.putBoolean("needFinishFragment", false);

            return new ContactsActivity(args);
        }
        if (RootNavigationSnapshot.CALLS.equals(destination)) {
            args.putBoolean("needFinishFragment", false);

            return new CallLogActivity(args);
        }
        if (RootNavigationSnapshot.SETTINGS.equals(destination)) {
            return new SettingsActivity(args);
        }
        if (RootNavigationSnapshot.PROFILE.equals(destination)) {
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            args.putBoolean("my_profile", true);

            return new ProfileActivity(args);
        }

        return null;
    }

    /* */

    public GlassTabView[] tabs;

    public void selectTab(int position, boolean animated) {
        for (int index = 0; index < rootNavigationState.size(); index++) {
            GlassTabView tab = getTab(rootNavigationState.getOrder().get(index));
            if (tab != null) {
                tab.setSelected(index == position, animated);
            }
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        for (int position = 0; position < rootNavigationState.size(); position++) {
            GlassTabView tab = getTab(rootNavigationState.getOrder().get(position));
            if (tab == null) {
                continue;
            }

            final float visibility = Math.max(0, 1f - Math.abs(position - animatedPosition));
            tab.setGestureSelectedOverride(visibility, allow);
        }
        tabsView.invalidate();
    }


    /* * */

    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default GlassSourceRenderNode getGlassSource() {
            return null;
        }

        default GlassSourceRenderNode getFrostedGlassSource() {
            return null;
        }

        default void setAudioMiniPlayerInset(float inset, int padding) {

        }

        default boolean getAudioMiniPlayerTopBounds(RectF out) {
            return false;
        }

        default void setAudioMiniPlayerTopActive(boolean active) {

        }

        default float getAudioMiniPlayerTopVisibility() {
            return 1.0f;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        if (!hasBottomNavigation()) {
            return false;
        }

        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            return delegate.canParentTabsSlide(ev, forward);

        }

        return false;
    }


    /* * */

    private int navigationBarHeight;
    private int statusBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        statusBarHeight = systemInsets.top;
        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = navigationBarHeight + updateLayoutHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);
        if (audioMiniPlayer != null) {
            audioMiniPlayer.setPadding(systemInsets.left, 0, systemInsets.right, 0);
        }

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_audioMiniPlayerPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            clearAllHiddenFragments();
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            GlassTabView profileTab = getTab(RootNavigationPolicy.PROFILE);
            if (profileTab != null) {
                profileTab.updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.contactsPermissionBadgeCheck) {
            checkContactsTabBadge();
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;

    @Override
    public boolean onFragmentCreate() {
        observersGroup = NotificationCenter.getInstance(currentAccount)
            .createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck)
            .addGlobal(NotificationCenter.appUpdateAvailable)
            .addGlobal(NotificationCenter.appUpdateLoading)
            .addGlobal(NotificationCenter.needSetDayNightTheme);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }

        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE) {
            checkUi_tabsPosition();
            checkUi_audioMiniPlayerPosition();
            checkUi_fadeView();
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }
        if (!hasBottomNavigation() || materialNavigationDocked) {
            fadeView.setVisibility(View.GONE);
            return;
        }

        final float animatedPosition = viewPager.getPositionAnimated();
        int profilePosition = rootNavigationState.positionOf(RootNavigationPolicy.PROFILE);
        final float isProfile = profilePosition < 0
                ? 0
                : 1f - MathUtils.clamp(Math.abs(profilePosition - animatedPosition), 0, 1);
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue();
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_tabsPosition() {
        if (!hasBottomNavigation()) {
            tabsViewWrapper.setVisibility(View.GONE);
            if (tabsView != null) {
                tabsView.setVisibility(View.GONE);
            }
            return;
        }

        tabsViewWrapper.setVisibility(View.VISIBLE);
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float factor = animatorTabsVisible.getFloatValue();
        final float scale = lerp(0.85f, 1f, factor);

        tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, factor));
        tabsView.setClickable(factor > 1);
        tabsView.setEnabled(factor > 1);
        tabsView.setAlpha(factor);
        tabsView.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
        if (dockedNavigationBackground != null) {
            dockedNavigationBackground.setAlpha(Math.round(255 * factor));
        }
    }

    private void checkUi_audioMiniPlayerPosition() {
        if (audioMiniPlayer == null || updateLayoutWrapper == null) {
            return;
        }

        boolean bottomNavigationVisible = hasBottomNavigation();
        int updateLayoutHeight = updateLayoutWrapper.isUpdateLayoutVisible()
                ? dp(UpdateLayoutWrapper.HEIGHT)
                : 0;
        int tabsHeight = bottomNavigationVisible
                ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS)
                : 0;
        int gap = !bottomNavigationVisible || materialNavigationDocked ? dp(12) : dp(4);
        float factor = bottomNavigationVisible
                ? animatorTabsVisible.getFloatValue()
                : 1.0f;
        float top = statusBarHeight + ActionBar.getCurrentActionBarHeight();
        float bottom = contentView.getHeight()
                - navigationBarHeight
                - updateLayoutHeight
                - tabsHeight
                - gap
                - dp(audioMiniPlayer.getBottomHeightDp());

        audioMiniPlayer.setHostBounds(top, bottom, factor);
        updateAudioMiniPlayerTopAnchor();
    }

    private void updateAudioMiniPlayerTopAnchor() {
        if (audioMiniPlayer == null || viewPager == null) {
            return;
        }

        FragmentState state = fragmentsArr.get(positionForDestination(RootNavigationPolicy.CHATS));
        BaseFragment chats = state == null ? null : state.fragment;
        View fragmentView = chats == null ? null : chats.getFragmentView();
        View page = fragmentView != null && fragmentView.getParent() instanceof View
                ? (View) fragmentView.getParent()
                : null;
        float zone = 0.0f;
        if (page != null
                && page.getParent() == viewPager
                && page.getVisibility() == View.VISIBLE
                && viewPager.getMeasuredWidth() > 0) {
            zone = MathUtils.clamp(
                    1.0f - Math.abs(page.getTranslationX()) / viewPager.getMeasuredWidth(),
                    0.0f,
                    1.0f
            );
        }

        float visibility = 1.0f;
        boolean resolvedTopBounds = false;
        if (chats instanceof TabFragmentDelegate) {
            TabFragmentDelegate delegate = (TabFragmentDelegate) chats;
            visibility = delegate.getAudioMiniPlayerTopVisibility();
            resolvedTopBounds = visibility >= 0.99f
                    && delegate.getAudioMiniPlayerTopBounds(audioMiniPlayerTopBounds);
            if (resolvedTopBounds) {
                audioMiniPlayerHasTopBounds = true;
            } else if (visibility >= 0.99f) {
                audioMiniPlayerHasTopBounds = false;
                audioMiniPlayerTopBounds.setEmpty();
            }
        }
        boolean fragmentPositionReady = fragmentView != null
                && ViewPositionWatcher.computeRectInParent(
                        fragmentView,
                        contentView,
                        audioMiniPlayerFragmentBounds
                );
        if (audioMiniPlayerHasTopBounds && fragmentPositionReady) {
            audioMiniPlayerResolvedTopBounds.set(audioMiniPlayerTopBounds);
            audioMiniPlayerResolvedTopBounds.offset(
                    audioMiniPlayerFragmentBounds.left,
                    audioMiniPlayerFragmentBounds.top
            );
        } else {
            audioMiniPlayerResolvedTopBounds.setEmpty();
        }

        audioMiniPlayer.setTopState(audioMiniPlayerResolvedTopBounds, zone, visibility);
    }

    private void updateAudioMiniPlayerInsets() {
        if (audioMiniPlayer == null) {
            return;
        }

        for (int index = 0; index < fragmentsArr.size(); index++) {
            BaseFragment fragment = fragmentsArr.valueAt(index).fragment;
            if (fragment instanceof TabFragmentDelegate) {
                applyAudioMiniPlayerInset(
                        (TabFragmentDelegate) fragment,
                        fragment instanceof DialogsActivity
                );
            }
        }
    }

    private void applyAudioMiniPlayerInset(TabFragmentDelegate delegate, boolean chats) {
        delegate.setAudioMiniPlayerInset(
                audioMiniPlayer.getVisibleOffset(chats),
                audioMiniPlayer.getTargetOffset(chats)
        );
        delegate.setAudioMiniPlayerTopActive(audioMiniPlayer.isTopTarget(chats));
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;

        return themeDescriptions;
    }

    /* * */

    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            if (!hasBottomNavigation()) {
                return;
            }
            if (!BottomNavigationSettings.followsContentVisibility(bottomNavigationMode)) {
                visible = true;
            }

            animatorTabsVisible.setValue(visible, true);
        }

        @Override
        public boolean openAccountSelector(View anchor) {
            return MainTabsActivity.this.openAccountSelector(anchor);
        }

        @Override
        public RootNavigationSnapshot getRootNavigationSnapshot() {
            return MainTabsActivity.this.getRootNavigationSnapshot();
        }

        @Override
        public boolean showsRootAudioMiniPlayer() {
            return audioMiniPlayerGeometry != null;
        }

        @Override
        public boolean navigateToRootDestination(
                RootNavigationSnapshot snapshot,
                String destination
        ) {
            return MainTabsActivity.this.navigateToRootDestination(snapshot, destination);
        }
    }


    /* Slide */

    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }


    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;

    private void showAccountChangeHint() {
        GlassTabView profileTab = getTab(RootNavigationPolicy.PROFILE);
        if (accountSwitchHintShown || !hasBottomNavigation() || profileTab == null) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            AndroidUtilities.runOnUIThread(() -> {
                GlassTabView currentProfileTab = getTab(RootNavigationPolicy.PROFILE);
                if (getContext() == null || currentProfileTab == null) return;

                final View v = currentProfileTab;
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                accountSwitchHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint.setTranslationY(-navigationBarHeight + dp(4));
                accountSwitchHint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                accountSwitchHint.setMultilineText(false);
                accountSwitchHint.setCloseButton(true);
                accountSwitchHint.setText(getString(R.string.SwitchAccountHint));
                accountSwitchHint.setJoint(1, -translate + 7.33f);
                contentView.addView(accountSwitchHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                accountSwitchHint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(accountSwitchHint));
                accountSwitchHint.setDuration(8000);
                accountSwitchHint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            }, 1500);
        }

        accountSwitchHintShown = true;
    }


    /* * */

    private final @NonNull GlassSourceColor iBlur3SourceColor;
    private final @Nullable GlassSourceRenderNode iBlur3SourceTabGlass;
    private final @Nullable GlassSourceRenderNode iBlur3SourceTabFrosted;

    /** The one the tabs draw now; null with the blur off, they are on the color then. */
    @Nullable
    private GlassSourceRenderNode getActiveTabSource() {
        final GlassSettings settings = GlassEngine.getCurrentGlassSettings();
        if (!settings.isBlurEnabled) {
            return null;
        }
        return settings.isLiquidGlassEnabled && Build.VERSION.SDK_INT >= 33 ? iBlur3SourceTabGlass : iBlur3SourceTabFrosted;
    }

    private final RectF fragmentPosition = new RectF();
    private final RectF tabsCapturePosition = new RectF();
    private final Runnable onFragmentGlassCaptured = this::blur3_onFragmentGlassCaptured;

    private void blur3_onFragmentGlassCaptured() {
        if (!materialNavigationDocked
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && iBlur3SourceTabGlass != null) {
            iBlur3SourceTabGlass.invalidateRenderer();
            iBlur3SourceTabFrosted.invalidateRenderer();
            blur3_invalidateBlur();
        }
    }

    /** The tabs go to the capture of the page under them: the full width, the place of the shown tabs whatever they do. */
    private void blur3_updateFragmentsGlass() {
        if (materialNavigationDocked
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || iBlur3SourceTabGlass == null
                || contentView == null) {
            return;
        }

        final int updateLayoutHeight = updateLayoutWrapper != null && updateLayoutWrapper.isUpdateLayoutVisible() ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int bottom = contentView.getHeight() - navigationBarHeight - updateLayoutHeight - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int top = bottom - dp(DialogsActivity.MAIN_TABS_HEIGHT);

        for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
            final BaseFragment fragment = fragmentsArr.valueAt(a).fragment;
            final View view = fragment.fragmentView;
            if (view == null || view.getWidth() <= 0 || !(fragment instanceof TabFragmentDelegate)) {
                continue;
            }
            if (top >= view.getHeight()) {
                fragment.setParentGlass(null, null);
            } else {
                tabsCapturePosition.set(0, top - dp(1), view.getWidth(), bottom + dp(1));
                fragment.setParentGlass(tabsCapturePosition, onFragmentGlassCaptured);
            }
        }
    }

    private void blur3_invalidateBlur() {
        if (materialNavigationDocked
                || !hasBottomNavigation()
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || iBlur3SourceTabGlass == null
                || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        blur3_updateFragmentsGlass();
        final GlassSourceRenderNode source = getActiveTabSource();
        if (source != null) {
            source.setSize(width, height);
            source.updateDisplayListIfNeeded();
        }
    }

    private boolean hasBottomNavigation() {
        return BottomNavigationSettings.isVisible(bottomNavigationMode);
    }

    private void blur3_updateFadeColors() {
        iBlur3SourceColor.setColor(getEstBackgroundColor());
        if (fadeView != null) {
            fadeView.invalidate();
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.setOutlineStyle(AppearanceSettings.getGlassOutlineStyle(currentAccount));
            tabsViewBackground.updateColors();
        }
        if (dockedNavigationBackground != null) {
            dockedNavigationBackground.setColors(
                    getThemedColor(Theme.key_windowBackgroundWhite),
                    getThemedColor(Theme.key_divider)
            );
        }
        blur3_invalidateBlur();
        if (fadeView != null) {
            fadeView.invalidate();
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                if (tabView != null) {
                    tabView.updateColorsLottie();
                }
            }
        }
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }

    @Override
    public void onThemeColorsChanged() {
        try {
            super.onThemeColorsChanged();
            blur3_updateColors();
            if (audioMiniPlayer != null) {
                audioMiniPlayer.updateColors();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** theme editor: colors this page shows (former ThemeDescription keys) */
    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        keys.add(Theme.key_dialogBackground);
        keys.add(Theme.key_divider);
        keys.add(Theme.key_windowBackgroundWhite);
        keys.add(Theme.key_inappPlayerBackground);
        keys.add(Theme.key_inappPlayerTitle);
        keys.add(Theme.key_inappPlayerPerformer);
        keys.add(Theme.key_inappPlayerPlayPause);
    }
}
