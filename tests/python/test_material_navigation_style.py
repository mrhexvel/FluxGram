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
GEOMETRY = JAVA / "ui/Components/ActiveIndicatorGeometry.java"
ADAPTER = JAVA / "ui/fluxgram/MaterialNavigationStyle.java"
LAYOUT = JAVA / "ui/MainTabsLayout.java"
TAB = JAVA / "ui/Components/glass/GlassTabView.java"
ACTIVITY = JAVA / "ui/MainTabsActivity.java"


class MaterialNavigationStyleTest(unittest.TestCase):
    def test_active_indicator_geometry_and_navigation_module_gate(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialNavigationStyleCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.EnumSet;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialVisualStyle;
                import org.telegram.ui.Components.ActiveIndicatorGeometry;
                import org.telegram.ui.fluxgram.MaterialNavigationStyle;

                public final class MaterialNavigationStyleCheck {
                    public static void main(String[] args) {
                        require(MaterialNavigationStyle.activeIndicatorGeometry(null) == null);
                        require(MaterialNavigationStyle.activeIndicatorGeometry(
                                MaterialVisualStyle.telegram()) == null);
                        require(MaterialNavigationStyle.activeIndicatorGeometry(resolve(
                                MaterialStylePolicy.Module.LISTS)) == null);

                        ActiveIndicatorGeometry geometry =
                                MaterialNavigationStyle.activeIndicatorGeometry(resolve(
                                        MaterialStylePolicy.Module.NAVIGATION));
                        require(geometry != null);
                        require(geometry.getWidthDp() == 56);
                        require(geometry.getHeightDp() == 32);
                        require(geometry.getCornerRadiusDp() == 16);
                        require(geometry.getHorizontalMarginDp() == 4);

                        requireThrows(() -> new ActiveIndicatorGeometry(0, 32, 16, 4));
                        requireThrows(() -> new ActiveIndicatorGeometry(56, 0, 16, 4));
                        requireThrows(() -> new ActiveIndicatorGeometry(56, 32, 17, 4));
                        requireThrows(() -> new ActiveIndicatorGeometry(56, 32, 16, -1));
                        requireThrows(() -> new ActiveIndicatorGeometry(8, 8, 4, 4));
                    }

                    private static MaterialVisualStyle.Snapshot resolve(
                            MaterialStylePolicy.Module module
                    ) {
                        return MaterialVisualStyle.resolve(MaterialStylePolicy.resolve(
                                MaterialStylePolicy.create(
                                        MaterialStylePolicy.Preset.ADVANCED,
                                        EnumSet.of(module)
                                )
                        ));
                    }

                    private static void requireThrows(Runnable runnable) {
                        try {
                            runnable.run();
                        } catch (IllegalArgumentException expected) {
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
                ["java", "-cp", str(root), "MaterialNavigationStyleCheck"],
                check=True,
            )

    def test_root_tabs_opt_in_without_material_policy_in_shared_components(self):
        geometry = GEOMETRY.read_text()
        adapter = ADAPTER.read_text()
        layout = LAYOUT.read_text()
        tab = TAB.read_text()
        activity = ACTIVITY.read_text()

        self.assertNotIn("android.", geometry)
        self.assertIn("MaterialStylePolicy.Module.NAVIGATION", adapter)

        for source in (layout, tab):
            self.assertNotIn("MaterialStyle", source)
            self.assertNotIn("FluxGram", source)

        self.assertIn(
            "setActiveIndicatorGeometry(@Nullable ActiveIndicatorGeometry geometry)",
            layout,
        )
        self.assertIn(
            "((GlassTabView) child).setActiveIndicatorGeometry(geometry)",
            layout,
        )
        self.assertIn("activeIndicatorGeometry.getWidthDp()", layout)
        self.assertIn("activeIndicatorGeometry.getHeightDp()", layout)
        self.assertIn("activeIndicatorGeometry.getWidthDp()", tab)
        self.assertIn("activeIndicatorGeometry.getHeightDp()", tab)
        self.assertIn("backupImageView.setTranslationX(offset)", tab)
        self.assertIn("public float getVisualWidth()", tab)
        self.assertIn("getCenterX(selected)", layout)
        self.assertIn("getVisualWidth(left)", layout)
        self.assertIn("getVisualWidth(right)", layout)
        self.assertNotIn("view.getX() + view.getWidth() * 0.5f", layout)
        self.assertIn("tmpRectF.set(0, 0, viewWidth, getHeight())", tab)
        self.assertIn("sWidth = availableWidth", layout)
        self.assertIn("MaterialNavigationStyle.activeIndicatorGeometry(", activity)
        self.assertIn("MaterialVisualStyleRuntime.getSnapshot()", activity)
        self.assertIn("tabsView.setActiveIndicatorGeometry(activeIndicatorGeometry)", activity)

        consumers = []
        for path in JAVA.rglob("*.java"):
            if path in (GEOMETRY, ADAPTER, LAYOUT, TAB):
                continue
            source = path.read_text()
            if "ActiveIndicatorGeometry" in source or "MaterialNavigationStyle" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual(["ui/MainTabsActivity.java"], sorted(consumers))


if __name__ == "__main__":
    unittest.main()
