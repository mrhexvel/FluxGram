from collections import Counter
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
POLICY = JAVA / "messenger/fluxgram/MaterialStylePolicy.java"
STYLE = JAVA / "messenger/fluxgram/MaterialVisualStyle.java"
RUNTIME = JAVA / "messenger/fluxgram/MaterialVisualStyleRuntime.java"
MINI_PLAYER_POSITION = JAVA / "messenger/fluxgram/AudioMiniPlayerPosition.java"
MINI_PLAYER_SETTINGS = JAVA / "messenger/fluxgram/AudioMiniPlayerSettings.java"
GEOMETRY = JAVA / "ui/Components/CircularProgressGeometry.java"
LINEAR_GEOMETRY = JAVA / "ui/Components/LinearProgressGeometry.java"
DETERMINATE_CIRCULAR_GEOMETRY = (
    JAVA / "ui/Components/DeterminateCircularProgressGeometry.java"
)
MINI_PLAYER_GEOMETRY = JAVA / "ui/Components/MiniPlayerGeometry.java"
PROGRESS_VIEW = JAVA / "ui/Components/RadialProgressView.java"
SEEK_BAR = JAVA / "ui/Components/SeekBarView.java"
PLAYER = JAVA / "ui/Components/AudioPlayerAlert.java"
CONTEXT_VIEW = JAVA / "ui/Components/FragmentContextView.java"
MINI_PLAYER = JAVA / "ui/fluxgram/MaterialAudioMiniPlayer.java"
MAIN_TABS = JAVA / "ui/MainTabsActivity.java"
CHAT = JAVA / "ui/ChatActivity.java"
DIALOGS = JAVA / "ui/DialogsActivity.java"
CALL_LOG = JAVA / "ui/CallLogActivity.java"
ADAPTER = JAVA / "ui/fluxgram/MaterialProgressStyle.java"
INSTALL_SHEET = JAVA / "ui/PluginInstallSheet.java"
APPLICATION_LOADER = JAVA / "messenger/ApplicationLoader.java"
PHOTO_VIEWER_WEB = JAVA / "ui/Components/PhotoViewerWebView.java"
WEB_PLAYER = JAVA / "ui/Components/WebPlayerView.java"
EMBED_SHEET = JAVA / "ui/Components/EmbedBottomSheet.java"
PHOTO_VIEWER = JAVA / "ui/PhotoViewer.java"
STATISTICS = JAVA / "ui/StatisticActivity.java"
STATIC_DRAWABLE = (
    ROOT
    / "TMessagesProj/src/main/res/drawable/ic_fluxgram_material_progress_static.xml"
)


class MaterialProgressStyleTest(unittest.TestCase):
    def test_radial_progress_consumers_remain_audited(self):
        expected = Counter({
            "ui/SettingsActivity.java": 1,
            "ui/ChatEditActivity.java": 1,
            "ui/PhotoAlbumPickerActivity.java": 1,
            "ui/Cells/GroupCallUserCell.java": 1,
            "ui/community/CommunityEditActivity.java": 1,
            "ui/Cells/ChatActionCell.java": 1,
            "ui/Cells/LoadingCell.java": 1,
            "ui/bots/ChatActivityBotWebViewButton.java": 1,
            "ui/ChannelCreateActivity.java": 1,
            "ui/Cells/ChatLoadingCell.java": 1,
            "ui/TwoStepVerificationSetupActivity.java": 1,
            "ui/StatisticActivity.java": 1,
            "ui/NewContactBottomSheet.java": 1,
            "ui/TwoStepVerificationActivity.java": 1,
            "ui/TooManyCommunitiesActivity.java": 1,
            "ui/GroupCreateFinalActivity.java": 1,
            "ui/ContactAddActivity.java": 1,
            "ui/LoginActivity.java": 2,
            "ui/ChatUsersActivity.java": 1,
            "ui/ChatActivity.java": 3,
            "ui/PluginInstallSheet.java": 1,
            "ui/Charts/view_data/LegendSignatureView.java": 1,
            "ui/PhotoViewer.java": 2,
            "ui/ChannelAdminLogActivity.java": 1,
            "ui/TopicsFragment.java": 1,
            "ui/GroupCallActivity.java": 1,
            "ui/ProfileActivity.java": 1,
            "ui/PhotoPickerActivity.java": 1,
            "ui/ActionBar/AlertDialog.java": 1,
            "ui/Components/EmptyTextProgressView.java": 1,
            "ui/Components/ChatAttachAlert.java": 1,
            "ui/Components/FragmentFloatingButton.java": 1,
            "ui/Components/JoinGroupAlert.java": 1,
            "ui/Components/EmojiView.java": 1,
            "ui/Components/PhotoViewerWebView.java": 1,
            "ui/Components/StickerEmptyView.java": 1,
            "ui/Components/WebPlayerView.java": 1,
            "ui/Components/EmbedBottomSheet.java": 1,
            "ui/Components/StickersAlert.java": 1,
        })
        actual = Counter()
        for source in JAVA.rglob("*.java"):
            count = source.read_text().count("new RadialProgressView")
            if count:
                actual[str(source.relative_to(JAVA))] = count

        self.assertEqual(actual, expected)

    def test_circular_geometry_resolves_only_for_progress(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialProgressStyleCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.EnumSet;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialVisualStyle;
                import org.telegram.messenger.fluxgram.AudioMiniPlayerPosition;
                import org.telegram.ui.Components.CircularProgressGeometry;
                import org.telegram.ui.Components.DeterminateCircularProgressGeometry;
                import org.telegram.ui.Components.LinearProgressGeometry;
                import org.telegram.ui.Components.MiniPlayerGeometry;
                import org.telegram.ui.fluxgram.MaterialProgressStyle;

                public final class MaterialProgressStyleCheck {
                    public static void main(String[] args) {
                        require(MaterialProgressStyle.circularGeometry(null) == null);
                        require(AudioMiniPlayerPosition.decode(null)
                                == AudioMiniPlayerPosition.BOTTOM);
                        require(AudioMiniPlayerPosition.decode("bottom")
                                == AudioMiniPlayerPosition.BOTTOM);
                        require(AudioMiniPlayerPosition.decode("top")
                                == AudioMiniPlayerPosition.TOP);
                        require(AudioMiniPlayerPosition.decode("unknown")
                                == AudioMiniPlayerPosition.BOTTOM);
                        require(MaterialProgressStyle.circularGeometry(
                                MaterialVisualStyle.telegram()) == null);
                        require(MaterialProgressStyle.circularGeometry(style(
                                MaterialStylePolicy.Module.CONTROLS)) == null);

                        CircularProgressGeometry geometry =
                                MaterialProgressStyle.circularGeometry(style(
                                        MaterialStylePolicy.Module.PROGRESS));
                        require(geometry != null);
                        require(geometry.getDiameterDp() == 48);
                        require(geometry.getTrackThicknessDp() == 4);
                        require(geometry.getTrackGapDp() == 4);
                        require(geometry.getWaveAmplitudeDp() == 1.6f);
                        require(geometry.getWavelengthDp() == 15);
                        require(geometry.getTotalDurationMs() == 6000);
                        require(geometry == MaterialProgressStyle.circularGeometry(style(
                                MaterialStylePolicy.Module.PROGRESS)));

                        LinearProgressGeometry linearGeometry =
                                MaterialProgressStyle.linearGeometry(style(
                                        MaterialStylePolicy.Module.PROGRESS));
                        require(linearGeometry != null);
                        require(linearGeometry.getTrackThicknessDp() == 4);
                        require(linearGeometry.getTrackGapDp() == 6);
                        require(linearGeometry.getThumbWidthDp() == 5);
                        require(linearGeometry.getDraggedThumbWidthDp() == 3);
                        require(linearGeometry.getThumbHeightDp() == 28);
                        require(linearGeometry.getWaveAmplitudeDp() == 4);
                        require(linearGeometry.getWavelengthDp() == 34);
                        require(linearGeometry.getStopIndicatorDiameterDp() == 4);
                        require(linearGeometry.getTouchTargetHeightDp() == 48);
                        require(MaterialProgressStyle.linearGeometry(style(
                                MaterialStylePolicy.Module.CONTROLS)) == null);

                        LinearProgressGeometry.Resolved linearResolved =
                                new LinearProgressGeometry.Resolved();
                        linearGeometry.resolve(100, 1, 0.4f, false, linearResolved);
                        require(close(linearResolved.getActiveStart(), 0));
                        require(close(linearResolved.getActiveEnd(), 31.5f));
                        require(close(linearResolved.getInactiveStart(), 48.5f));
                        require(close(linearResolved.getInactiveEnd(), 100));
                        require(close(linearResolved.getThumbCenter(), 40));

                        linearGeometry.resolve(100, 1, 0.4f, true, linearResolved);
                        require(close(linearResolved.getActiveStart(), 68.5f));
                        require(close(linearResolved.getActiveEnd(), 100));
                        require(close(linearResolved.getInactiveStart(), 0));
                        require(close(linearResolved.getInactiveEnd(), 51.5f));
                        require(close(linearResolved.getThumbCenter(), 60));

                        MiniPlayerGeometry miniPlayerGeometry =
                                MaterialProgressStyle.miniPlayerGeometry(style(
                                         MaterialStylePolicy.Module.PROGRESS));
                        require(miniPlayerGeometry != null);
                        require(miniPlayerGeometry.getHeightDp() == 52);
                        require(miniPlayerGeometry.getHorizontalMarginDp() == 12);
                        require(miniPlayerGeometry.getVerticalMarginDp() == 12);
                        require(miniPlayerGeometry.getCornerRadiusDp() == 20);
                        require(miniPlayerGeometry.getCoverSizeDp() == 36);
                        require(miniPlayerGeometry.getCoverRadiusDp() == 10);
                        require(miniPlayerGeometry.getControlButtonWidthDp() == 44);
                        require(miniPlayerGeometry.getPlayButtonWidthDp() == 48);
                        require(miniPlayerGeometry.getTopGapDp() == 8);
                        DeterminateCircularProgressGeometry playbackGeometry =
                                miniPlayerGeometry.getProgressGeometry();
                        require(playbackGeometry != null);
                        require(playbackGeometry.getDiameterDp() == 40);
                        require(playbackGeometry.getTrackThicknessDp() == 3);
                        require(playbackGeometry.getTrackGapDp() == 0);
                        require(playbackGeometry.getWaveAmplitudeDp() == 1.3f);
                        require(playbackGeometry.getWavelengthDp() == 10.5f);
                        require(MaterialProgressStyle.miniPlayerGeometry(style(
                                 MaterialStylePolicy.Module.CONTROLS)) == null);

                        CircularProgressGeometry.Resolved resolved =
                                new CircularProgressGeometry.Resolved();
                        geometry.resolve(0, resolved);
                        require(close(resolved.getRotationDegrees(), 0));
                        require(close(resolved.getEndFraction(), 0.10f));

                        geometry.resolve(1500, resolved);
                        require(close(resolved.getRotationDegrees(), 360));
                        require(resolved.getEndFraction() > 0.60f);
                        require(resolved.getEndFraction() < 0.80f);

                        geometry.resolve(3000, resolved);
                        require(close(resolved.getRotationDegrees(), 720));
                        require(close(resolved.getEndFraction(), 0.87f));

                        geometry.resolve(6000, resolved);
                        require(close(resolved.getRotationDegrees(), 0));
                        require(close(resolved.getEndFraction(), 0.10f));
                    }

                    private static MaterialVisualStyle.Snapshot style(
                            MaterialStylePolicy.Module module
                    ) {
                        return MaterialVisualStyle.resolve(MaterialStylePolicy.resolve(
                                MaterialStylePolicy.create(
                                        MaterialStylePolicy.Preset.ADVANCED,
                                        EnumSet.of(module)
                                )
                        ));
                    }

                    private static boolean close(float first, float second) {
                        return Math.abs(first - second) < 0.01f;
                    }

                    private static void require(boolean value) {
                        if (!value) {
                            throw new AssertionError();
                        }
                    }
                }
            """))

            subprocess.run(
                [
                    "javac",
                    "-d",
                    str(root),
                    str(POLICY),
                    str(STYLE),
                    str(MINI_PLAYER_POSITION),
                    str(GEOMETRY),
                    str(LINEAR_GEOMETRY),
                    str(DETERMINATE_CIRCULAR_GEOMETRY),
                    str(MINI_PLAYER_GEOMETRY),
                    str(ADAPTER),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "MaterialProgressStyleCheck"],
                check=True,
            )

    def test_progress_consumer_is_bounded_and_fallback_is_preserved(self):
        adapter = ADAPTER.read_text()
        geometry = GEOMETRY.read_text()
        progress_view = PROGRESS_VIEW.read_text()
        seek_bar = SEEK_BAR.read_text()
        player = PLAYER.read_text()
        mini_player = MINI_PLAYER.read_text()
        mini_player_settings = MINI_PLAYER_SETTINGS.read_text()
        context_view = CONTEXT_VIEW.read_text()
        main_tabs = MAIN_TABS.read_text()
        chat = CHAT.read_text()
        dialogs = DIALOGS.read_text()
        call_log = CALL_LOG.read_text()
        install_sheet = INSTALL_SHEET.read_text()
        static_drawable = STATIC_DRAWABLE.read_text()

        self.assertIn("MaterialStylePolicy.Module.PROGRESS", adapter)
        self.assertIn("MaterialVisualStyle.ShapeRole.FULL", adapter)
        self.assertNotIn("MaterialStyleSettings", adapter)
        self.assertNotIn("Theme", adapter)
        self.assertNotIn("android.", adapter)

        self.assertNotIn("android.", geometry)
        self.assertNotIn("android.", LINEAR_GEOMETRY.read_text())
        self.assertNotIn("android.", DETERMINATE_CIRCULAR_GEOMETRY.read_text())
        self.assertNotIn("android.", MINI_PLAYER_GEOMETRY.read_text())
        self.assertIn("Math.floorMod(playTimeMs, totalDurationMs)", geometry)
        self.assertIn("1080.0f * animationFraction", geometry)
        self.assertIn("delay <= 4500", geometry)
        self.assertIn("0.10f + 0.77f * activeFraction", geometry)

        self.assertIn(
            "setCircularProgressGeometry(CircularProgressGeometry geometry)",
            progress_view,
        )
        self.assertIn("initializeDefaultCircularProgressGeometry(", progress_view)
        self.assertIn(
            "circularProgressGeometry = defaultCircularProgressGeometry",
            progress_view,
        )
        self.assertIn("clearDefaultCircularProgressGeometry()", progress_view)
        self.assertIn("value != AndroidUtilities.dp(40)", progress_view)
        self.assertIn("static final HashSet<RadialProgressView>", progress_view)
        self.assertIn("circularProgressViews.add(this)", progress_view)
        self.assertIn("circularProgressViews.remove(this)", progress_view)
        self.assertIn("0x2e000000", progress_view)
        self.assertIn("circularProgressGeometry != null && noProgress", progress_view)
        self.assertIn("circularProgressGeometry.resolve(", progress_view)
        self.assertIn("circularProgressGeometry == null\n                ? size", progress_view)
        self.assertIn("circularProgressGeometry == null ? 0.0f", progress_view)
        self.assertIn("strokeWidth / 2.0f", progress_view)
        self.assertIn("SystemClock.uptimeMillis()", progress_view)
        self.assertIn("Math.max(0, newTime - lastCircularProgressUpdateTime)", progress_view)
        self.assertIn("Settings.Global.ANIMATOR_DURATION_SCALE", progress_view)
        self.assertIn("dt / circularProgressAnimatorDurationScale", progress_view)
        self.assertIn("registerContentObserver(", progress_view)
        self.assertIn("unregisterContentObserver(", progress_view)
        self.assertIn("circularProgressAnimatorDurationScale == 0.0f", progress_view)
        self.assertIn("drawCircularProgressStatic(", progress_view)
        self.assertIn("R.drawable.ic_fluxgram_material_progress_static", progress_view)
        self.assertNotIn("ValueAnimator.areAnimatorsEnabled()", progress_view)
        self.assertIn("ensureCircularProgressPath(radius)", progress_view)
        self.assertIn("circularProgressPath.cubicTo(", progress_view)
        self.assertIn("circularProgressPathMeasure.getSegment(", progress_view)
        self.assertIn("(int) (circumference / wavelength)", progress_view)
        self.assertIn("canvas.drawArc(", progress_view)
        self.assertIn("float trackStart = endFraction + edgeInset", progress_view)
        self.assertIn("float activeStart = edgeInset", progress_view)
        self.assertIn("float activeEnd = endFraction - edgeInset", progress_view)
        self.assertNotIn("MaterialStylePolicy", progress_view)
        self.assertNotIn("MaterialVisualStyle", progress_view)

        self.assertIn("MaterialProgressStyle.linearGeometry(", player)
        self.assertIn("MaterialVisualStyleRuntime.getSnapshot()", player)
        self.assertIn("seekBarView.setLinearProgressGeometry(", player)
        self.assertIn("seekBarView.setMirrorProgressInRtl(false)", player)
        self.assertIn("playbackProgressGeometry == null ? 67 : 60", player)
        self.assertIn("clipSliderTrackForTimestamps(", seek_bar)
        self.assertIn("float minDuration = AndroidUtilities.dp(4) / trackWidth", seek_bar)
        self.assertIn("timestamps.get(index).first - from <= minDuration", seek_bar)
        self.assertIn("Theme.key_player_progressCachedBackground", seek_bar)
        self.assertIn("setMirrorProgressInRtl(boolean mirrorProgressInRtl)", seek_bar)
        self.assertIn("setLinearProgressPlaying(boolean playing)", seek_bar)
        self.assertIn("drawLinearWave(", seek_bar)
        self.assertIn("linearProgressGeometry.getDraggedThumbWidthDp()", seek_bar)
        self.assertIn("linearProgressGeometry.getStopIndicatorDiameterDp()", seek_bar)
        self.assertIn("float stopCenter = isRtl ? trackStart : trackEnd", seek_bar)
        self.assertIn("linearProgressPhase + 6.0f * deltaSeconds", seek_bar)
        self.assertIn("postInvalidateOnAnimation()", seek_bar)
        self.assertIn("outerPaint1.setStyle(Paint.Style.STROKE)", seek_bar)
        self.assertIn("outerPaint1.setStyle(Paint.Style.FILL)", seek_bar)
        self.assertLess(
            seek_bar.index("if (clippedForTimestamps) {\n            canvas.restore();"),
            seek_bar.index("float thumbCenter = trackStart"),
        )
        self.assertIn("class MaterialAudioMiniPlayer", mini_player)
        self.assertIn("implements NotificationCenter.NotificationCenterDelegate", mini_player)
        self.assertIn("geometry.getHeightDp()", mini_player)
        self.assertIn("geometry.getHorizontalMarginDp()", mini_player)
        self.assertIn("geometry.getVerticalMarginDp()", mini_player)
        self.assertIn("new SpannableStringBuilder", mini_player)
        self.assertIn('text.append(" · ").append(artistText)', mini_player)
        self.assertIn("new PlaybackRingView", mini_player)
        self.assertIn("new PlaybackWaveView", mini_player)
        self.assertIn("new PlaybackButtonView", mini_player)
        self.assertIn("topCardBackground.setColor(backgroundColor)", mini_player)
        self.assertIn("topTitleView.setTextColor(titleColor)", mini_player)
        self.assertIn("topWaveView.setColors(", mini_player)
        self.assertIn("topPlayButton.setColor(controlColor)", mini_player)
        self.assertIn("FRAME_DELAY_MS = 48", mini_player)
        self.assertIn("!LiteMode.isPowerSaverApplied()", mini_player)
        self.assertIn("MediaController.getInstance().getAudioInfo()", mini_player)
        self.assertIn("MediaController.getInstance().playPreviousMessage()", mini_player)
        self.assertIn("MediaController.getInstance().playNextMessage()", mini_player)
        self.assertIn("NotificationCenter.messagePlayingDidStart", mini_player)
        self.assertIn("NotificationCenter.messagePlayingProgressDidChanged", mini_player)
        self.assertIn("NotificationCenter.fileLoaded", mini_player)
        self.assertIn("messageObject.isMusic()", mini_player)
        self.assertIn("LiteMode.isPowerSaverApplied()", mini_player)
        self.assertIn("postDelayed(waveFrame, WAVE_FRAME_DELAY_MS)", mini_player)
        self.assertIn("ViewConfiguration.getLongPressTimeout()", mini_player)
        self.assertIn("HapticFeedbackConstants.CLOCK_TICK", mini_player)
        self.assertIn("MAGNET_ZONE", mini_player)
        self.assertIn("ACTIVATION_WAVE_INTENSITY = 0.16f", mini_player)
        self.assertIn("activatedCard.drawableHotspotChanged(localX, localY)", mini_player)
        self.assertIn("activatedCard.getLocationInWindow(cardLocation)", mini_player)
        self.assertIn("activatedCard == card", mini_player)
        self.assertIn("LaunchActivity.makeRipple(", mini_player)
        self.assertIn("setDragLifted(true, true)", mini_player)
        self.assertNotIn("ValueAnimator.areAnimatorsEnabled()", mini_player)
        self.assertIn("new SpringAnimation(value)", mini_player)
        self.assertIn("setDampingRatio(0.82f)", mini_player)
        self.assertIn("event.getRawY()", mini_player)
        self.assertIn("MotionEvent.ACTION_CANCEL", mini_player)
        self.assertIn("class DraggableCard extends LinearLayout", mini_player)
        self.assertIn("super.dispatchTouchEvent(cancelEvent)", mini_player)
        self.assertIn("AudioMiniPlayerPosition.TOP", mini_player)
        self.assertIn("setTopState(RectF bounds, float zone, float visibility)", mini_player)
        self.assertIn("topBounds.top", mini_player)
        self.assertIn("placementProgress * effectiveTopZone", mini_player)
        self.assertIn("topBounds.isEmpty() ? 0.0f : topZone", mini_player)
        self.assertIn("boolean availabilityChanged = topBounds.isEmpty() != bounds.isEmpty()", mini_player)
        self.assertIn("|| topBounds.isEmpty()", mini_player)
        self.assertIn("finishPositionImmediately", mini_player)
        self.assertIn("topPlayButton.setEnabled(interactionsEnabled)", mini_player)
        self.assertIn("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS", mini_player)
        self.assertIn("Settings.Global.ANIMATOR_DURATION_SCALE", mini_player)
        self.assertIn("LiteMode.addOnPowerSaverAppliedListener", mini_player)
        self.assertIn("LiteMode.removeOnPowerSaverAppliedListener", mini_player)
        self.assertIn("getAudioMiniPlayerTopBounds(RectF out)", main_tabs)
        self.assertIn("getAudioMiniPlayerTopBounds(audioMiniPlayerTopBounds)", main_tabs)
        self.assertIn("audioMiniPlayerHasTopBounds", main_tabs)
        self.assertIn("visibility >= 0.99f", main_tabs)
        self.assertIn("ViewPositionWatcher.computeRectInParent(", main_tabs)
        self.assertNotIn("applyPlacementGeometry", mini_player)
        self.assertIn("AudioMiniPlayerSettings.getPosition()", main_tabs)
        self.assertIn("AudioMiniPlayerSettings::setPosition", main_tabs)
        self.assertIn("ActionBar.getCurrentActionBarHeight()", main_tabs)
        self.assertNotIn("getAudioMiniPlayerTopAnchor()", main_tabs)
        self.assertNotIn("setAudioMiniPlayerTopPadding(", main_tabs)
        self.assertIn("getAudioMiniPlayerTopBounds(RectF out)", dialogs)
        self.assertIn("actionBar.getTitleTextView2()", dialogs)
        self.assertIn("actionBar.getSubtitleTextView()", dialogs)
        self.assertIn("AppearanceSettings.centersActionBarTitle(currentAccount)", dialogs)
        self.assertIn("LayoutHelper.createFrame(148, 48)", mini_player)
        self.assertIn("new InsetDrawable(topCardBackground, 0, dp(6), 0, dp(6))", mini_player)
        top_background = mini_player.index("topCardContent.setBackground(")
        top_padding = mini_player.index("topCardContent.setPadding(dp(10), 0, dp(8), 0)")
        self.assertLess(top_background, top_padding)
        self.assertIn("topCardContent.addView(topPlayButton, LayoutHelper.createLinear(22, 48))", mini_player)
        self.assertIn("new TouchDelegate(bounds, topPlayButton)", mini_player)
        self.assertIn("topPlayButton.setBackground(null)", mini_player)
        self.assertIn("new MorphingCardView(context)", mini_player)
        self.assertIn("updateMorphingCard(transitionProgress, progress * morphAlpha)", mini_player)
        self.assertIn("adaptBottomContent(transitionProgress)", mini_player)
        self.assertIn("adaptViewToCenter(", mini_player)
        self.assertIn("+ hostBottom", mini_player)
        self.assertIn("float adaptation = progress", mini_player)
        self.assertIn("lerp(bottomWidth, topWidth, progress)", mini_player)
        self.assertIn("12.0f / 14.0f", mini_player)
        self.assertIn("24.0f / geometry.getCoverSizeDp()", mini_player)
        self.assertIn("playButton.setTransitionProgress(transitionProgress)", mini_player)
        self.assertIn("14.0f / 16.0f", mini_player)
        self.assertIn("card.setClipToOutline(false)", mini_player)
        self.assertIn("topCard.setAlpha(progress * topPrimaryAlpha)", mini_player)
        self.assertIn("float ringAlpha = 1.0f - smoothStep", mini_player)
        self.assertIn("WAVE_HEIGHTS = {6.0f, 12.0f, 18.0f, 11.0f, 7.0f}", mini_player)
        self.assertIn("Math.sin(Math.PI * progress)", mini_player)
        self.assertIn("canvas.drawRoundRect(bounds, radius, radius, paint)", mini_player)
        self.assertIn("topCard.setForeground(null)", mini_player)
        self.assertIn("updateMoveUpAccessibilityAction()", mini_player)
        self.assertIn("actionBar.getMenuView()", dialogs)
        self.assertIn("animatorActionModeVisible.getFloatValue()", dialogs)
        self.assertIn("audioMiniPlayerActionBarBounds.centerX()", dialogs)
        self.assertIn("Math.min(dp(220), side * 2.0f)", dialogs)
        self.assertIn("setAudioMiniPlayerTopActive(boolean active)", dialogs)
        self.assertIn("actionBar.setTitleRightMargin(", dialogs)
        self.assertIn("!audioMiniPlayerTopActive", dialogs)
        self.assertIn("audioMiniPlayer.isTopTarget(chats)", main_tabs)
        self.assertNotIn("t += audioMiniPlayerTopPadding", dialogs)
        self.assertIn("putString(POSITION_KEY", mini_player_settings)
        self.assertIn(".commit()", mini_player_settings)
        self.assertNotIn("MaterialProgressStyle", mini_player)
        self.assertNotIn("MaterialVisualStyle", mini_player)
        self.assertIn("setupAudioMiniPlayer(context)", main_tabs)
        self.assertIn("new MaterialAudioMiniPlayer(", main_tabs)
        self.assertIn("checkUi_audioMiniPlayerPosition()", main_tabs)
        self.assertIn("animatorTabsVisible.getFloatValue()", main_tabs)
        self.assertIn("args.putBoolean(\"hasRootAudioMiniPlayer\"", main_tabs)
        self.assertIn("new AudioPlayerAlert(getParentActivity(), resourceProvider).show()", main_tabs)
        self.assertNotIn("setAudioPlayerGeometry(", context_view)
        self.assertNotIn("MiniPlayerGeometry", context_view)
        self.assertIn("setSupportsMusicPlayer(boolean value)", context_view)
        self.assertIn("fragmentContextView.setSupportsMusicPlayer(", dialogs)
        self.assertIn("fragmentContextView.setSupportsMusicPlayer(", call_log)
        self.assertIn("arguments.getBoolean(\"hasRootAudioMiniPlayer\", false)", call_log)
        self.assertNotIn("MaterialProgressStyle.miniPlayerGeometry(", dialogs)
        self.assertNotIn("MaterialProgressStyle.miniPlayerGeometry(", chat)

        self.assertIn("M17.65,6.35", static_drawable)

        self.assertIn("progress.setSize(dp(42))", install_sheet)
        self.assertIn("progress.setCircularProgressGeometry(", install_sheet)
        self.assertIn("progress.setCircularProgressTrackColor(", install_sheet)
        self.assertIn("MaterialVisualStyleRuntime.getSnapshot()", install_sheet)
        self.assertEqual(install_sheet.count("progress.setCircularProgressGeometry("), 1)

        application_loader = APPLICATION_LOADER.read_text()
        self.assertIn(
            "RadialProgressView.initializeDefaultCircularProgressGeometry(",
            application_loader,
        )
        self.assertIn(
            "MaterialProgressStyle.circularGeometry(MaterialVisualStyleRuntime.getSnapshot())",
            application_loader,
        )
        for consumer in (
            PHOTO_VIEWER_WEB,
            WEB_PLAYER,
            EMBED_SHEET,
            PHOTO_VIEWER,
            STATISTICS,
        ):
            self.assertIn(
                "setCircularProgressGeometry(null)",
                consumer.read_text(),
            )

        runtime = RUNTIME.read_text()
        self.assertNotIn("MaterialProgressStyle", runtime)


if __name__ == "__main__":
    unittest.main()
