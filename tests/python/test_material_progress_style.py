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
GEOMETRY = JAVA / "ui/Components/CircularProgressGeometry.java"
PROGRESS_VIEW = JAVA / "ui/Components/RadialProgressView.java"
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
                import org.telegram.ui.Components.CircularProgressGeometry;
                import org.telegram.ui.fluxgram.MaterialProgressStyle;

                public final class MaterialProgressStyleCheck {
                    public static void main(String[] args) {
                        require(MaterialProgressStyle.circularGeometry(null) == null);
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
                    str(GEOMETRY),
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
        install_sheet = INSTALL_SHEET.read_text()
        static_drawable = STATIC_DRAWABLE.read_text()

        self.assertIn("MaterialStylePolicy.Module.PROGRESS", adapter)
        self.assertIn("MaterialVisualStyle.ShapeRole.FULL", adapter)
        self.assertNotIn("MaterialStyleSettings", adapter)
        self.assertNotIn("Theme", adapter)
        self.assertNotIn("android.", adapter)

        self.assertNotIn("android.", geometry)
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
