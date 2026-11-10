from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "TMessagesProj/src/main/java"
JAVA = SOURCE_ROOT / "org/telegram"
POLICY = JAVA / "messenger/fluxgram/MaterialStylePolicy.java"
STYLE = JAVA / "messenger/fluxgram/MaterialVisualStyle.java"
RUNTIME = JAVA / "messenger/fluxgram/MaterialVisualStyleRuntime.java"
ACTIVITY = JAVA / "ui/FluxGramSettingsActivity.java"
HEADER = JAVA / "ui/Components/FluxGramSettingsHeaderCell.java"


class MaterialVisualStyleTest(unittest.TestCase):
    def test_roles_fallback_immutability_and_process_snapshot(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialVisualStyleCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.ArrayList;
                import java.util.EnumSet;
                import java.util.List;
                import java.util.concurrent.CountDownLatch;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Module;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Preset;
                import org.telegram.messenger.fluxgram.MaterialVisualStyle;
                import org.telegram.messenger.fluxgram.MaterialVisualStyleRuntime;

                public final class MaterialVisualStyleCheck {
                    public static void main(String[] args) {
                        MaterialVisualStyle.Snapshot telegram = MaterialVisualStyle.telegram();
                        require(telegram.getEnabledModules().isEmpty());
                        require(telegram.getShape(Module.SURFACES, MaterialVisualStyle.ShapeRole.LARGE) == null);
                        require(telegram.getTypography(Module.LISTS, MaterialVisualStyle.TypographyRole.BODY_MEDIUM) == null);
                        require(telegram.getMotion(Module.MOTION, MaterialVisualStyle.MotionRole.SHORT_STATE) == null);

                        MaterialStylePolicy.EffectiveSnapshot effective = MaterialStylePolicy.resolve(
                                MaterialStylePolicy.create(
                                        Preset.ADVANCED,
                                        EnumSet.of(Module.SURFACES, Module.LISTS, Module.MOTION)
                                )
                        );
                        MaterialVisualStyle.Snapshot style = MaterialVisualStyle.resolve(effective);
                        require(style.isEnabled(Module.SURFACES));
                        require(!style.isEnabled(Module.CONTROLS));
                        requireShapes(style);
                        require(style.getShape(Module.SURFACES, MaterialVisualStyle.ShapeRole.FULL).isFull());
                        requireTypography(style);
                        require(style.getTypography(Module.CONTROLS, MaterialVisualStyle.TypographyRole.BODY_LARGE) == null);
                        requireSpacing(style);
                        requireElevation(style);
                        requireStates(style);
                        requireMotion(style);
                        require(style.getShape(null, MaterialVisualStyle.ShapeRole.LARGE) == null);
                        require(style.getShape(Module.SURFACES, null) == null);
                        requireThrows(() -> style.getEnabledModules().clear());

                        requireConcurrentInitialization(effective);
                        MaterialVisualStyle.Snapshot repeated = MaterialVisualStyleRuntime.initialize(
                                MaterialStylePolicy.resolve(MaterialStylePolicy.defaults())
                        );
                        require(MaterialVisualStyleRuntime.getSnapshot() == repeated);
                    }

                    private static void requireShapes(MaterialVisualStyle.Snapshot style) {
                        int[] radii = {0, 4, 8, 12, 16, 28, 0};
                        MaterialVisualStyle.ShapeRole[] roles = MaterialVisualStyle.ShapeRole.values();
                        require(roles.length == radii.length);

                        for (int index = 0; index < roles.length; index++) {
                            MaterialVisualStyle.Shape value = style.getShape(Module.SURFACES, roles[index]);
                            require(value.getRadiusDp() == radii[index]);
                            require(value.isFull() == (roles[index] == MaterialVisualStyle.ShapeRole.FULL));
                        }
                    }

                    private static void requireTypography(MaterialVisualStyle.Snapshot style) {
                        int[][] expected = {
                                {22, 28, 400},
                                {16, 24, 500},
                                {16, 24, 400},
                                {14, 20, 400},
                                {14, 20, 500},
                                {12, 16, 500}
                        };
                        MaterialVisualStyle.TypographyRole[] roles = MaterialVisualStyle.TypographyRole.values();
                        require(roles.length == expected.length);

                        for (int index = 0; index < roles.length; index++) {
                            MaterialVisualStyle.Typography value = style.getTypography(Module.LISTS, roles[index]);
                            require(value.getSizeSp() == expected[index][0]);
                            require(value.getLineHeightSp() == expected[index][1]);
                            require(value.getWeight() == expected[index][2]);
                        }
                    }

                    private static void requireSpacing(MaterialVisualStyle.Snapshot style) {
                        int[] expected = {4, 8, 12, 16, 24, 32};
                        MaterialVisualStyle.SpacingRole[] roles = MaterialVisualStyle.SpacingRole.values();
                        require(roles.length == expected.length);

                        for (int index = 0; index < roles.length; index++) {
                            require(style.getSpacing(Module.SURFACES, roles[index]) == expected[index]);
                        }
                    }

                    private static void requireElevation(MaterialVisualStyle.Snapshot style) {
                        int[] expected = {0, 1, 3, 6, 8, 12};
                        MaterialVisualStyle.ElevationRole[] roles = MaterialVisualStyle.ElevationRole.values();
                        require(roles.length == expected.length);

                        for (int index = 0; index < roles.length; index++) {
                            require(style.getElevation(Module.SURFACES, roles[index]) == expected[index]);
                        }
                    }

                    private static void requireStates(MaterialVisualStyle.Snapshot style) {
                        float[] amounts = {0.08f, 0.10f, 0.10f, 0.16f, 0.38f, 0.12f, 0.0f};
                        MaterialVisualStyle.StateTreatment[] treatments = {
                                MaterialVisualStyle.StateTreatment.OVERLAY,
                                MaterialVisualStyle.StateTreatment.OVERLAY,
                                MaterialVisualStyle.StateTreatment.OVERLAY,
                                MaterialVisualStyle.StateTreatment.OVERLAY,
                                MaterialVisualStyle.StateTreatment.CONTENT_ALPHA,
                                MaterialVisualStyle.StateTreatment.CONTAINER_ALPHA,
                                MaterialVisualStyle.StateTreatment.SEMANTIC
                        };
                        MaterialVisualStyle.StateRole[] roles = MaterialVisualStyle.StateRole.values();
                        require(roles.length == amounts.length);

                        for (int index = 0; index < roles.length; index++) {
                            MaterialVisualStyle.State value = style.getState(Module.SURFACES, roles[index]);
                            require(value.getTreatment() == treatments[index]);
                            require(close(value.getAmount(), amounts[index]));
                        }
                    }

                    private static void requireMotion(MaterialVisualStyle.Snapshot style) {
                        float[][] expected = {
                                {100, 0.2f, 0.0f, 0.0f, 1.0f},
                                {300, 0.0f, 0.0f, 0.0f, 1.0f},
                                {200, 0.3f, 0.0f, 1.0f, 1.0f},
                                {500, 0.05f, 0.7f, 0.1f, 1.0f},
                                {200, 0.3f, 0.0f, 0.8f, 0.15f}
                        };
                        MaterialVisualStyle.MotionRole[] roles = MaterialVisualStyle.MotionRole.values();
                        require(roles.length == expected.length);

                        for (int index = 0; index < roles.length; index++) {
                            MaterialVisualStyle.Motion value = style.getMotion(Module.MOTION, roles[index]);
                            require(value.getDurationMs() == (int) expected[index][0]);
                            require(close(value.getControlX1(), expected[index][1]));
                            require(close(value.getControlY1(), expected[index][2]));
                            require(close(value.getControlX2(), expected[index][3]));
                            require(close(value.getControlY2(), expected[index][4]));
                        }
                    }

                    private static void requireConcurrentInitialization(
                            MaterialStylePolicy.EffectiveSnapshot first
                    ) {
                        MaterialStylePolicy.EffectiveSnapshot second = MaterialStylePolicy.resolve(
                                MaterialStylePolicy.create(Preset.ADVANCED, EnumSet.of(Module.CONTROLS))
                        );
                        CountDownLatch ready = new CountDownLatch(16);
                        CountDownLatch start = new CountDownLatch(1);
                        List<MaterialVisualStyle.Snapshot> results = new ArrayList<>();
                        List<Thread> threads = new ArrayList<>();

                        for (int index = 0; index < 16; index++) {
                            final MaterialStylePolicy.EffectiveSnapshot candidate = index % 2 == 0
                                    ? first
                                    : second;
                            Thread thread = new Thread(() -> {
                                ready.countDown();

                                try {
                                    start.await();
                                } catch (InterruptedException exception) {
                                    throw new AssertionError(exception);
                                }

                                MaterialVisualStyle.Snapshot value =
                                        MaterialVisualStyleRuntime.initialize(candidate);

                                synchronized (results) {
                                    results.add(value);
                                }
                            });
                            threads.add(thread);
                            thread.start();
                        }

                        try {
                            ready.await();
                            start.countDown();

                            for (Thread thread : threads) {
                                thread.join();
                            }
                        } catch (InterruptedException exception) {
                            throw new AssertionError(exception);
                        }

                        require(results.size() == threads.size());
                        MaterialVisualStyle.Snapshot winner = results.get(0);

                        for (MaterialVisualStyle.Snapshot result : results) {
                            require(result == winner);
                        }

                        require(MaterialVisualStyleRuntime.getSnapshot() == winner);
                        require(winner.isEnabled(Module.SURFACES) || winner.isEnabled(Module.CONTROLS));
                    }

                    private static boolean close(float first, float second) {
                        return Math.abs(first - second) < 0.0001f;
                    }

                    private static void requireThrows(Runnable runnable) {
                        try {
                            runnable.run();
                        } catch (UnsupportedOperationException expected) {
                            return;
                        }

                        throw new AssertionError();
                    }

                    private static void require(boolean value) {
                        if (!value) {
                            throw new AssertionError();
                        }
                    }
                }
            """))

            subprocess.run(
                ["javac", "-d", str(root), str(POLICY), str(STYLE), str(RUNTIME), str(check)],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "MaterialVisualStyleCheck"],
                check=True,
            )

    def test_foundation_stays_android_independent_and_pilot_is_bounded(self):
        for path in (STYLE, RUNTIME):
            source = path.read_text()
            self.assertNotIn("android.", source)
            self.assertNotIn("org.telegram.ui", source)
            self.assertNotIn("SharedPreferences", source)
            self.assertNotIn("MaterialStyleSettings", source)

        consumers = []
        for path in SOURCE_ROOT.rglob("*.java"):
            if path in (STYLE, RUNTIME):
                continue
            source = path.read_text()
            if "MaterialVisualStyle" in source or "MaterialVisualStyleRuntime" in source:
                consumers.append(path.relative_to(SOURCE_ROOT).as_posix())

        self.assertEqual(
            [
                "org/telegram/messenger/ApplicationLoader.java",
                "org/telegram/messenger/fluxgram/icons/MaterialSymbolsCatalog.java",
                "org/telegram/ui/CacheControlActivity.java",
                "org/telegram/ui/Components/AudioPlayerAlert.java",
                "org/telegram/ui/Components/FluxGramFolderPreviewCell.java",
                "org/telegram/ui/Components/FluxGramSettingsHeaderCell.java",
                "org/telegram/ui/DialogsActivity.java",
                "org/telegram/ui/FluxGramSettingsActivity.java",
                "org/telegram/ui/MainTabsActivity.java",
                "org/telegram/ui/PluginInstallSheet.java",
                "org/telegram/ui/fluxgram/MaterialControlsStyle.java",
                "org/telegram/ui/fluxgram/MaterialListStyle.java",
                "org/telegram/ui/fluxgram/MaterialNavigationStyle.java",
                "org/telegram/ui/fluxgram/MaterialProgressStyle.java",
            ],
            sorted(consumers),
        )

        activity = ACTIVITY.read_text()
        header = HEADER.read_text()
        self.assertIn("MaterialVisualStyleRuntime.initialize(", activity)
        self.assertIn("MaterialStylePolicy.resolve(savedMaterialStyle)", activity)
        self.assertIn("header.getPreferredHeightDp()", activity)
        self.assertIn("MaterialStylePolicy.Module.SURFACES", header)
        self.assertIn("MaterialVisualStyle.ShapeRole.EXTRA_LARGE", header)
        self.assertIn("MaterialVisualStyle.TypographyRole.TITLE_LARGE", header)
        self.assertIn("MaterialVisualStyle.TypographyRole.BODY_MEDIUM", header)
        self.assertIn("materialSurface ? iconShape.getRadiusDp() : 24", header)
        self.assertIn("materialSurface ? titleTypography.getSizeSp() : 25", header)
        self.assertIn("materialSurface ? bodyTypography.getSizeSp() : 13", header)
        self.assertIn("view.getTextPaint().getFontMetricsInt(null)", header)
        self.assertIn("Math.max(rolePixels, fontPixels)", header)
        self.assertIn("icon instanceof AdaptiveIconDrawable", header)
        self.assertIn("adaptiveIcon.getBackground()", header)
        self.assertIn("adaptiveIcon.getForeground()", header)
        self.assertIn("Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)", header)
        self.assertIn("layers.draw(new Canvas(bitmap))", header)


if __name__ == "__main__":
    unittest.main()
