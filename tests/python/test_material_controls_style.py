from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
APPLICATION = JAVA / "messenger/ApplicationLoader.java"
POLICY = JAVA / "messenger/fluxgram/MaterialStylePolicy.java"
STYLE = JAVA / "messenger/fluxgram/MaterialVisualStyle.java"
ADAPTER = JAVA / "ui/fluxgram/MaterialControlsStyle.java"
CHECK_BOX = JAVA / "ui/Components/CheckBoxBase.java"
CHECK_BOX_2 = JAVA / "ui/Components/CheckBox2.java"
CHECK_BOX_CELL = JAVA / "ui/Cells/CheckBoxCell.java"
CHECK_BOX_GEOMETRY = JAVA / "ui/Components/CheckBoxGeometry.java"
DISCRETE_SLIDER_GEOMETRY = JAVA / "ui/Components/DiscreteSliderGeometry.java"
SLIDER_GEOMETRY = JAVA / "ui/Components/SliderGeometry.java"
RADIO_BUTTON = JAVA / "ui/Components/RadioButton.java"
RADIO_COLOR_CELL = JAVA / "ui/Cells/RadioColorCell.java"
SWITCH = JAVA / "ui/Components/Switch.java"
SWITCH_GEOMETRY = JAVA / "ui/Components/SwitchGeometry.java"
SEEK_BAR = JAVA / "ui/Components/SeekBarView.java"
SLIDE_CHOOSE = JAVA / "ui/Components/SlideChooseView.java"
TEXT_CHECK = JAVA / "ui/Cells/TextCheckCell.java"
UNIVERSAL_ADAPTER = JAVA / "ui/Components/UniversalAdapter.java"
SETTINGS = JAVA / "ui/FluxGramSettingsActivity.java"
SESSION_SHEET = JAVA / "ui/SessionBottomSheet.java"
CACHE_CONTROL = JAVA / "ui/CacheControlActivity.java"


class MaterialControlsStyleTest(unittest.TestCase):
    def test_switch_geometry_resolves_only_for_controls(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialControlsStyleCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.EnumSet;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialVisualStyle;
                import org.telegram.ui.Components.CheckBoxGeometry;
                import org.telegram.ui.Components.DiscreteSliderGeometry;
                import org.telegram.ui.Components.SliderGeometry;
                import org.telegram.ui.Components.SwitchGeometry;
                import org.telegram.ui.fluxgram.MaterialControlsStyle;

                public final class MaterialControlsStyleCheck {
                    public static void main(String[] args) {
                        require(MaterialControlsStyle.switchGeometry(null) == null);
                        require(MaterialControlsStyle.switchGeometry(
                                MaterialVisualStyle.telegram()) == null);

                        MaterialVisualStyle.Snapshot icons = style(
                                MaterialStylePolicy.Module.MATERIAL_SYMBOLS
                        );
                        require(MaterialControlsStyle.switchGeometry(icons) == null);
                        require(MaterialControlsStyle.checkBoxGeometry(icons) == null);

                        CheckBoxGeometry checkBoxGeometry = MaterialControlsStyle.checkBoxGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        );
                        require(checkBoxGeometry != null);
                        require(checkBoxGeometry.getContainerSizeDp() == 18);
                        require(checkBoxGeometry.getCornerRadiusDp() == 2);
                        require(checkBoxGeometry.getOutlineWidthDp() == 2);
                        require(checkBoxGeometry.getTransitionScale() == 0.6f);
                        require(checkBoxGeometry.getCheckDurationMs() == 350);
                        require(checkBoxGeometry.getUncheckDurationMs() == 150);
                        require(checkBoxGeometry.getFadeDurationMs() == 50);
                        require(checkBoxGeometry == MaterialControlsStyle.checkBoxGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        ));

                        SliderGeometry sliderGeometry = MaterialControlsStyle.sliderGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        );
                        require(sliderGeometry != null);
                        require(sliderGeometry.getTrackHeightDp() == 16);
                        require(sliderGeometry.getThumbWidthDp() == 4);
                        require(sliderGeometry.getThumbHeightDp() == 36);
                        require(sliderGeometry.getThumbTrackGapDp() == 6);
                        require(sliderGeometry.getTrackInsideCornerRadiusDp() == 2);
                        require(sliderGeometry.getStopIndicatorSizeDp() == 4);
                        require(sliderGeometry.getPressedThumbWidthRatio() == 0.5f);
                        require(sliderGeometry.getTouchTargetHeightDp() == 48);
                        require(sliderGeometry == MaterialControlsStyle.sliderGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        ));
                        require(MaterialControlsStyle.sliderGeometry(icons) == null);

                        DiscreteSliderGeometry discreteGeometry = MaterialControlsStyle.discreteSliderGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        );
                        require(discreteGeometry != null);
                        require(discreteGeometry.getSliderGeometry() == sliderGeometry);
                        require(discreteGeometry.getTickSizeDp() == 4);
                        require(discreteGeometry == MaterialControlsStyle.discreteSliderGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        ));
                        require(MaterialControlsStyle.discreteSliderGeometry(icons) == null);

                        SwitchGeometry geometry = MaterialControlsStyle.switchGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        );
                        require(geometry != null);
                        require(geometry.getTrackWidthDp() == 44);
                        require(geometry.getTrackHeightDp() == 24);
                        require(geometry.getTrackOutlineWidthDp() == 2);
                        require(geometry.getCellOuterMarginDp() == 16);
                        require(geometry.getCellEndInsetDp() == 76);
                        require(geometry == MaterialControlsStyle.switchGeometry(
                                style(MaterialStylePolicy.Module.CONTROLS)
                        ));

                        require(geometry.getUncheckedThumbDiameterDp() == 14);
                        require(geometry.getCheckedThumbDiameterDp() == 20);
                        require(geometry.getMorphThumbWidthDp() == 24);
                        require(geometry.getMorphThumbHeightDp() == 18);

                        requireResolved(resolve(geometry, 44, 24, 1, 0, 14, 14), 0, 0, 44, 24, 12, 12, 14, 14);
                        requireResolved(resolve(geometry, 44, 24, 1, 0.5f, 24, 18), 0, 0, 44, 24, 22, 12, 24, 18);
                        requireResolved(resolve(geometry, 44, 24, 1, 1, 20, 20), 0, 0, 44, 24, 32, 12, 20, 20);
                        requireResolved(resolve(geometry, 44, 24, 1, 0.25f, 24, 18), 0, 0, 44, 24, 17, 12, 24, 18);
                        requireResolved(resolve(geometry, 22, 12, 1, 1, 20, 20), 0, 0, 22, 12, 16, 6, 10, 10);
                        requireThrows(() -> resolve(geometry, 44, 24, 0, 0, 14, 14));
                    }

                    private static SwitchGeometry.Resolved resolve(
                            SwitchGeometry geometry,
                            float width,
                            float height,
                            float density,
                            float positionProgress,
                            float thumbWidthDp,
                            float thumbHeightDp
                    ) {
                        SwitchGeometry.Resolved output = new SwitchGeometry.Resolved();
                        geometry.resolve(
                                width,
                                height,
                                density,
                                positionProgress,
                                thumbWidthDp,
                                thumbHeightDp,
                                output
                        );
                        return output;
                    }

                    private static void requireResolved(
                            SwitchGeometry.Resolved value,
                            float x,
                            float y,
                            float width,
                            float height,
                            float thumbX,
                            float thumbY,
                            float thumbWidth,
                            float thumbHeight
                    ) {
                        require(close(value.getX(), x));
                        require(close(value.getY(), y));
                        require(close(value.getWidth(), width));
                        require(close(value.getHeight(), height));
                        require(close(value.getThumbCenterX(), thumbX));
                        require(close(value.getThumbCenterY(), thumbY));
                        require(close(value.getThumbWidth(), thumbWidth));
                        require(close(value.getThumbHeight(), thumbHeight));
                        require(close(value.getThumbRadius(), Math.min(thumbWidth, thumbHeight) / 2.0f));
                    }

                    private static boolean close(float first, float second) {
                        return Math.abs(first - second) < 0.001f;
                    }

                    private static void requireThrows(Runnable runnable) {
                        try {
                            runnable.run();
                        } catch (IllegalArgumentException expected) {
                            return;
                        }

                        throw new AssertionError();
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
                    str(CHECK_BOX_GEOMETRY),
                    str(DISCRETE_SLIDER_GEOMETRY),
                    str(SLIDER_GEOMETRY),
                    str(SWITCH_GEOMETRY),
                    str(ADAPTER),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "MaterialControlsStyleCheck"],
                check=True,
            )

    def test_switch_consumer_is_bounded_and_defaults_are_preserved(self):
        adapter = ADAPTER.read_text()
        check_box = CHECK_BOX.read_text()
        check_box_2 = CHECK_BOX_2.read_text()
        check_box_cell = CHECK_BOX_CELL.read_text()
        radio_button = RADIO_BUTTON.read_text()
        radio_color_cell = RADIO_COLOR_CELL.read_text()
        switch = SWITCH.read_text()
        seek_bar = SEEK_BAR.read_text()
        slide_choose = SLIDE_CHOOSE.read_text()
        text_check = TEXT_CHECK.read_text()
        universal = UNIVERSAL_ADAPTER.read_text()
        settings = SETTINGS.read_text()
        session_sheet = SESSION_SHEET.read_text()
        application = APPLICATION.read_text()
        cache_control = CACHE_CONTROL.read_text()

        self.assertIn("new SwitchGeometry(", adapter)
        self.assertIn("new CheckBoxGeometry(", adapter)
        self.assertIn("new SliderGeometry(", adapter)
        self.assertIn("new DiscreteSliderGeometry(SLIDER_GEOMETRY, 4)", adapter)
        self.assertIn("MaterialStylePolicy.Module.CONTROLS", adapter)
        self.assertIn("MaterialVisualStyle.ShapeRole.FULL", adapter)
        self.assertNotIn("MaterialStyleSettings", adapter)
        self.assertNotIn("Theme", adapter)
        self.assertNotIn("android.", adapter)

        self.assertIn("setGeometry(CheckBoxGeometry geometry)", check_box)
        self.assertIn("drawGeometry(Canvas canvas)", check_box)
        self.assertIn("CubicBezierInterpolator.EmphasizedDecelerate", check_box)
        self.assertIn("CubicBezierInterpolator.EmphasizedAccelerate", check_box)
        self.assertIn("geometry.getFadeDurationMs()", check_box)
        self.assertIn("geometryFadeAnimator.setInterpolator(new LinearInterpolator())", check_box)
        self.assertIn("if (geometry != null && !animated)", check_box)
        self.assertIn("private final Paint geometryPaint", check_box)
        self.assertIn("private final Paint geometryOutlinePaint", check_box)
        self.assertIn("setGeometry(CheckBoxGeometry geometry)", check_box_2)
        self.assertIn("setCheckBoxGeometry(CheckBoxGeometry geometry)", check_box_cell)

        self.assertIn("private int size = AndroidUtilities.dp(16)", radio_button)
        self.assertIn("paint.setStrokeWidth(AndroidUtilities.dp(2))", radio_button)
        self.assertIn("size / 4", radio_button)
        self.assertIn("radioButton.setSize(AndroidUtilities.dp(20))", radio_color_cell)

        self.assertIn("if (geometry == null)", switch)
        self.assertIn("height * 0.385f", switch)
        self.assertIn("geometry.resolve(", switch)
        self.assertEqual(switch.count("geometry.resolve("), 1)
        self.assertIn("checkAnimator.setDuration(geometry == null ? 200 : 250)", switch)
        self.assertIn("MATERIAL_SWITCH_INTERPOLATOR", switch)
        self.assertNotIn("MATERIAL_SWITCH_STANDARD_INTERPOLATOR", switch)
        self.assertNotIn("checkAnimator.setInterpolator(MATERIAL_SWITCH_INTERPOLATOR)", switch)
        self.assertIn("checked ? 150 : 100", switch)
        self.assertIn("checked ? 100 : 150", switch)
        self.assertIn("animator.playSequentially(toMorph, toTarget)", switch)
        self.assertNotIn("materialPressTransition", switch)
        self.assertNotIn("animateMaterialPressed", switch)
        self.assertIn("animateMaterialThumbShape(newCheckedState)", switch)
        self.assertIn("setMaterialThumbWidthDp(diameter)", switch)
        self.assertIn("setMaterialThumbHeightDp(diameter)", switch)
        self.assertIn("if (a == 0 && materialGeometry == null && rippleDrawable != null)", switch)
        self.assertIn("geometry.getTrackOutlineWidthDp()", switch)
        self.assertIn("canvasToDraw.drawRoundRect(rectF, thumbRadius, thumbRadius, paint)", switch)
        self.assertIn("LocaleController.isRTL", switch)
        self.assertIn("info.setClassName(\"android.widget.Switch\")", switch)
        self.assertIn("geometry = defaultGeometry;", switch)
        self.assertIn("initializeDefaultGeometry(SwitchGeometry geometry)", switch)
        self.assertIn("if (defaultGeometryInitialized)", switch)
        self.assertIn("getLayoutWidthDp(int fallbackWidthDp)", switch)
        self.assertIn("getLayoutHeightDp(int fallbackHeightDp)", switch)
        self.assertIn("getOuterMarginDp(int fallbackMarginDp)", switch)
        self.assertIn("getCellEndInsetDp(int fallbackInsetDp)", switch)
        self.assertNotIn("MaterialStylePolicy", switch)
        self.assertNotIn("MaterialVisualStyle", switch)

        self.assertIn(
            "Switch.initializeDefaultGeometry(MaterialControlsStyle.switchGeometry(",
            application,
        )
        on_create = application.split("public void onCreate()", 1)[1]
        post_init = application.split("public static void postInitApplication()", 1)[1].split(
            "public ApplicationLoader()", 1
        )[0]
        self.assertIn("Switch.initializeDefaultGeometry(", on_create)
        self.assertNotIn("Switch.initializeDefaultGeometry(", post_init)
        self.assertLess(
            application.index("MaterialVisualStyleRuntime.initialize("),
            application.index("Switch.initializeDefaultGeometry("),
        )

        self.assertIn("setSliderGeometry(SliderGeometry geometry)", seek_bar)
        self.assertIn("if (sliderGeometry != null)", seek_bar)
        self.assertIn("drawSliderGeometry(canvas, thumbX)", seek_bar)
        self.assertIn("sliderGeometry.getPressedThumbWidthRatio()", seek_bar)
        self.assertIn("sliderGeometry.getStopIndicatorSizeDp()", seek_bar)
        self.assertIn("sliderGeometry.getTouchTargetHeightDp()", seek_bar)
        self.assertIn("gap -= (thumbWidth - pressedThumbWidth) / 2.0f", seek_bar)
        self.assertIn("return sliderGeometry != null && LocaleController.isRTL", seek_bar)
        self.assertIn("Math.abs(stopIndicatorX - centerX) > gap + thumbWidth / 2.0f", seek_bar)
        self.assertIn("sliderGeometry = defaultSliderGeometry", seek_bar)
        self.assertIn("initializeDefaultGeometry(SliderGeometry geometry)", seek_bar)
        self.assertIn("private int clampThumbX(int value)", seek_bar)
        self.assertIn("boolean hasMinimum = minProgress > 0 && !twoSided", seek_bar)
        self.assertIn("drawSliderGeometryTrackWithAlpha", seek_bar)
        self.assertIn(
            "innerPaint1.setColor(themedInnerColor = getThemedColor(Theme.key_player_progressBackground))",
            seek_bar,
        )
        self.assertIn("sliderGeometry == null", seek_bar)
        self.assertIn("? (int) (minProgress * range)", seek_bar)
        self.assertNotIn("MaterialStylePolicy", seek_bar)
        self.assertNotIn("MaterialVisualStyle", seek_bar)

        self.assertIn("setDiscreteSliderGeometry(DiscreteSliderGeometry geometry)", slide_choose)
        self.assertIn("drawDiscreteSlider(canvas)", slide_choose)
        self.assertIn("discreteSliderGeometry.getTickSizeDp()", slide_choose)
        self.assertIn("materialCenterForIndex", slide_choose)
        self.assertIn("LocaleController.isRTL", slide_choose)
        self.assertIn("geometry.getPressedThumbWidthRatio()", slide_choose)
        self.assertIn("event.getAction() == MotionEvent.ACTION_UP", slide_choose)
        self.assertIn("Math.round(indexTouch) != selectedIndex", slide_choose)
        self.assertIn("materialRect.width() > minimumSegmentWidth", slide_choose)
        self.assertIn("AndroidUtilities.dp(25), textPaint", slide_choose)
        self.assertNotIn("MaterialStylePolicy", slide_choose)
        self.assertNotIn("MaterialVisualStyle", slide_choose)

        keep_media_case, max_cache_case = cache_control.split(
            "case VIEW_TYPE_MAX_CACHE_SIZE:", 1
        )
        self.assertNotIn("setDiscreteSliderGeometry", keep_media_case)
        self.assertIn("slideChooseView2.setDiscreteSliderGeometry(", max_cache_case)
        self.assertIn("MaterialControlsStyle.discreteSliderGeometry(", max_cache_case)
        self.assertEqual(1, cache_control.count("setDiscreteSliderGeometry("))

        self.assertIn(
            "SeekBarView.initializeDefaultGeometry(MaterialControlsStyle.sliderGeometry(",
            application,
        )
        self.assertIn("SeekBarView.initializeDefaultGeometry(", on_create)
        self.assertNotIn("SeekBarView.initializeDefaultGeometry(", post_init)
        self.assertLess(
            application.index("MaterialVisualStyleRuntime.initialize("),
            application.index("SeekBarView.initializeDefaultGeometry("),
        )

        self.assertIn("setSwitchGeometry(SwitchGeometry geometry)", text_check)
        self.assertIn("switchGeometry == null ? 37", text_check)
        self.assertIn("switchGeometry == null ? 20", text_check)
        self.assertIn(
            "switchGeometry == null ? 22 : switchGeometry.getCellOuterMarginDp()",
            text_check,
        )
        self.assertIn("switchGeometry.getCellEndInsetDp()", text_check)
        self.assertIn("if (drawCheckRipple && switchGeometry == null)", text_check)
        self.assertIn("checkBox.setDrawRipple(pressed)", text_check)

        self.assertIn("setCheckCellSwitchGeometry(SwitchGeometry geometry)", universal)
        self.assertIn("setRoundCheckBoxGeometry(CheckBoxGeometry geometry)", universal)
        self.assertIn("checkBoxCell.setCheckBoxGeometry(roundCheckBoxGeometry)", universal)
        self.assertIn("checkCell.setSwitchGeometry(checkCellSwitchGeometry)", universal)
        self.assertEqual(
            universal.count("if (checkCellSwitchGeometry != null)"),
            2,
        )
        self.assertNotIn("MaterialStylePolicy", universal)
        self.assertNotIn("MaterialVisualStyle", universal)

        self.assertIn(
            "MaterialControlsStyle.switchGeometry(materialVisualStyle)",
            settings,
        )
        self.assertIn(
            "MaterialControlsStyle.checkBoxGeometry(materialVisualStyle)",
            settings,
        )
        self.assertIn(
            "MaterialControlsStyle.sliderGeometry(materialVisualStyle)",
            settings,
        )
        self.assertEqual(settings.count("sliderGeometry,"), 4)
        self.assertIn("switchView.getGeometry() != null && LocaleController.isRTL", session_sheet)
        self.assertIn("iconParams.gravity = Gravity.RIGHT", session_sheet)
        self.assertIn("textParams.leftMargin = textEndInset", session_sheet)
        self.assertIn("textParams.rightMargin = dp(64)", session_sheet)
        self.assertIn("getMeasuredWidth() - dp(64)", session_sheet)

    def test_all_seek_bar_consumers_have_explicit_global_classification(self):
        expected = {
            "ui/ArticleViewer.java",
            "ui/BlurSettingsBottomSheet.java",
            "ui/Cells/BrightnessControlCell.java",
            "ui/Cells/MaxFileSizeCell.java",
            "ui/Cells/SlideIntChooseView.java",
            "ui/Components/AudioPlayerAlert.java",
            "ui/Components/FloatingDebug/FloatingDebugView.java",
            "ui/Components/FluxGramAvatarGeometryPreviewCell.java",
            "ui/Components/FluxGramFabShapePreviewCell.java",
            "ui/Components/FluxGramMessageAppearancePreviewCell.java",
            "ui/Components/FluxGramSectionRadiusCell.java",
            "ui/DebugRecordingCanvasReplayFragment.java",
            "ui/GLIconSettingsView.java",
            "ui/LiteModeSettingsActivity.java",
            "ui/PluginSettingsActivity.java",
            "ui/SaveToGallerySettingsActivity.java",
            "ui/Stories/DialogStoriesCell.java",
            "ui/ThemeActivity.java",
            "ui/ThemePreviewActivity.java",
        }
        consumers = {
            path.relative_to(JAVA).as_posix()
            for path in JAVA.rglob("*.java")
            if path != SEEK_BAR and "new SeekBarView(" in path.read_text()
        }

        self.assertEqual(expected, consumers)
        sources = "\n".join((JAVA / path).read_text() for path in sorted(consumers))
        self.assertEqual(28, sources.count("new SeekBarView("))
        self.assertEqual(15, sources.count("setSliderGeometry(null)"))
        self.assertEqual(4, sources.count("setSliderGeometry(sliderGeometry)"))

        for path in {
            "ui/BlurSettingsBottomSheet.java",
            "ui/Components/AudioPlayerAlert.java",
            "ui/Components/FloatingDebug/FloatingDebugView.java",
            "ui/DebugRecordingCanvasReplayFragment.java",
            "ui/GLIconSettingsView.java",
            "ui/Stories/DialogStoriesCell.java",
            "ui/ThemePreviewActivity.java",
        }:
            self.assertIn("setSliderGeometry(null)", (JAVA / path).read_text(), path)

        for path in {
            "ui/Cells/BrightnessControlCell.java",
            "ui/Cells/MaxFileSizeCell.java",
            "ui/Cells/SlideIntChooseView.java",
            "ui/LiteModeSettingsActivity.java",
            "ui/PluginSettingsActivity.java",
            "ui/SaveToGallerySettingsActivity.java",
        }:
            self.assertIn("getPreferredHeightDp", (JAVA / path).read_text(), path)

    def test_all_standard_switch_consumers_use_effective_geometry(self):
        expected = {
            "ui/Cells/AvailableReactionCell.java",
            "ui/Cells/CheckBoxUserCell.java",
            "ui/Cells/NotificationsCheckCell.java",
            "ui/Cells/PollCreateCheckCell.java",
            "ui/Cells/TextCell.java",
            "ui/Cells/TextCheckCell.java",
            "ui/Cells/TextCheckCell2.java",
            "ui/Components/FluxGramPluginCell.java",
            "ui/Components/MultilineTextCheckCell.java",
            "ui/Components/PhonebookShareAlert.java",
            "ui/LiteModeSettingsActivity.java",
            "ui/SessionBottomSheet.java",
        }
        consumers = {
            path.relative_to(JAVA).as_posix()
            for path in JAVA.rglob("*.java")
            if path != SWITCH and "new Switch(" in path.read_text()
        }

        self.assertEqual(expected, consumers)
        for relative in consumers:
            source = (JAVA / relative).read_text()
            self.assertTrue(
                "getLayoutWidthDp(37)" in source
                or "switchGeometry == null ? 37" in source,
                relative,
            )
            self.assertNotIn("createFrame(37, 20", source, relative)
            self.assertNotIn("createFrame(37, 40", source, relative)
            self.assertNotIn("createFrameRelatively(37, 20", source, relative)
            self.assertNotIn("createLinear(37, 40", source, relative)


if __name__ == "__main__":
    unittest.main()
