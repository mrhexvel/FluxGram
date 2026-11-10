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
STATIC_DRAWABLE = (
    ROOT
    / "TMessagesProj/src/main/res/drawable/ic_fluxgram_material_progress_static.xml"
)


class MaterialProgressStyleTest(unittest.TestCase):
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
        self.assertIn("card.drawableHotspotChanged(localX, localY)", mini_player)
        self.assertIn("card.getLocationInWindow(cardLocation)", mini_player)
        self.assertIn("LaunchActivity.makeRipple(", mini_player)
        self.assertIn("setDragLifted(true, true)", mini_player)
        self.assertNotIn("ValueAnimator.areAnimatorsEnabled()", mini_player)
        self.assertIn("new SpringAnimation(value)", mini_player)
        self.assertIn("setDampingRatio(0.82f)", mini_player)
        self.assertIn("event.getRawY()", mini_player)
        self.assertIn("MotionEvent.ACTION_CANCEL", mini_player)
        self.assertIn("AudioMiniPlayerPosition.TOP", mini_player)
        self.assertIn("setTopState(float anchor, float zone, float shift, float visibility)", mini_player)
        self.assertNotIn("applyPlacementGeometry", mini_player)
        self.assertIn("AudioMiniPlayerSettings.getPosition()", main_tabs)
        self.assertIn("AudioMiniPlayerSettings::setPosition", main_tabs)
        self.assertIn("ActionBar.getCurrentActionBarHeight()", main_tabs)
        self.assertIn("getAudioMiniPlayerTopAnchor()", main_tabs)
        self.assertIn("setAudioMiniPlayerTopPadding(", main_tabs)
        self.assertIn("t += audioMiniPlayerTopPadding", dialogs)
        self.assertIn("viewPages[0].listView.topPadding", dialogs)
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

        runtime = RUNTIME.read_text()
        self.assertNotIn("MaterialProgressStyle", runtime)


if __name__ == "__main__":
    unittest.main()
