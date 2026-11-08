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
ADAPTER = JAVA / "ui/fluxgram/MaterialControlsStyle.java"
SWITCH = JAVA / "ui/Components/Switch.java"
SWITCH_GEOMETRY = JAVA / "ui/Components/SwitchGeometry.java"
TEXT_CHECK = JAVA / "ui/Cells/TextCheckCell.java"
UNIVERSAL_ADAPTER = JAVA / "ui/Components/UniversalAdapter.java"
SETTINGS = JAVA / "ui/FluxGramSettingsActivity.java"


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
        switch = SWITCH.read_text()
        text_check = TEXT_CHECK.read_text()
        universal = UNIVERSAL_ADAPTER.read_text()
        settings = SETTINGS.read_text()

        self.assertIn("new SwitchGeometry(", adapter)
        self.assertIn("MaterialStylePolicy.Module.CONTROLS", adapter)
        self.assertIn("MaterialVisualStyle.ShapeRole.FULL", adapter)
        self.assertNotIn("MaterialStyleSettings", adapter)
        self.assertNotIn("Theme", adapter)
        self.assertNotIn("android.", adapter)

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
        self.assertIn("checkCell.setSwitchGeometry(checkCellSwitchGeometry)", universal)
        self.assertNotIn("MaterialStylePolicy", universal)
        self.assertNotIn("MaterialVisualStyle", universal)

        self.assertIn(
            "MaterialControlsStyle.switchGeometry(materialVisualStyle)",
            settings,
        )


if __name__ == "__main__":
    unittest.main()
