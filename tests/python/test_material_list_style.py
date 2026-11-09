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
GEOMETRY = JAVA / "ui/Components/FilterChipGeometry.java"
LIST_STYLE = JAVA / "ui/fluxgram/MaterialListStyle.java"
FILTER_TABS = JAVA / "ui/Components/FilterTabsView.java"
DIALOGS = JAVA / "ui/DialogsActivity.java"
PREVIEW = JAVA / "ui/Components/FluxGramFolderPreviewCell.java"


class MaterialListStyleTest(unittest.TestCase):
    def test_filter_chip_geometry_and_list_module_gate(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialListStyleCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.EnumSet;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialVisualStyle;
                import org.telegram.ui.Components.FilterChipGeometry;
                import org.telegram.ui.fluxgram.MaterialListStyle;

                public final class MaterialListStyleCheck {
                    public static void main(String[] args) {
                        require(MaterialListStyle.filterChipGeometry(null) == null);
                        require(MaterialListStyle.filterChipGeometry(MaterialVisualStyle.telegram()) == null);

                        MaterialVisualStyle.Snapshot controls = resolve(
                                MaterialStylePolicy.Module.CONTROLS
                        );
                        require(MaterialListStyle.filterChipGeometry(controls) == null);

                        FilterChipGeometry geometry = MaterialListStyle.filterChipGeometry(resolve(
                                MaterialStylePolicy.Module.LISTS
                        ));
                        require(geometry != null);
                        require(geometry.getHeightDp() == 32);
                        require(geometry.getUnselectedCornerRadiusDp() == 8);
                        require(geometry.getSelectedCornerRadiusDp() == 16);
                        require(geometry.getGapDp() == 8);
                        require(close(geometry.getUnselectedContainerAlpha(), 0.08f));
                        require(close(geometry.getSelectedContainerAlpha(), 0.22f));

                        requireThrows(() -> new FilterChipGeometry(0, 8, 16, 8, 0.08f, 0.12f));
                        requireThrows(() -> new FilterChipGeometry(32, 8, 7, 8, 0.08f, 0.12f));
                        requireThrows(() -> new FilterChipGeometry(32, 8, 17, 8, 0.08f, 0.12f));
                        requireThrows(() -> new FilterChipGeometry(32, 8, 16, 8, -0.01f, 0.12f));
                        requireThrows(() -> new FilterChipGeometry(32, 8, 16, 8, 0.08f, 1.01f));
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

                    private static boolean close(float first, float second) {
                        return Math.abs(first - second) < 0.0001f;
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
                    str(LIST_STYLE),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "MaterialListStyleCheck"],
                check=True,
            )

    def test_filter_tabs_remains_neutral_and_consumers_are_bounded(self):
        geometry = GEOMETRY.read_text()
        list_style = LIST_STYLE.read_text()
        filter_tabs = FILTER_TABS.read_text()
        dialogs = DIALOGS.read_text()
        preview = PREVIEW.read_text()

        self.assertNotIn("android.", geometry)
        self.assertNotIn("MaterialStyle", filter_tabs)
        self.assertNotIn("FluxGram", filter_tabs)
        self.assertIn("setFilterChipGeometry(FilterChipGeometry geometry)", filter_tabs)
        self.assertIn("child == listView && filterChipGeometry == null", filter_tabs)
        self.assertIn("getSelectedCornerRadiusDp()", filter_tabs)
        self.assertIn(
            "(filterChipGeometry != null ? 5.0f : fallbackSpacingDp) * visibleProgress",
            filter_tabs,
        )
        self.assertIn(
            "getAnimatedThemeColor(unactiveTextColorKey, aUnactiveTextColorKey)",
            filter_tabs,
        )
        self.assertIn(
            "getAnimatedThemeColor(activeTextColorKey, aActiveTextColorKey)",
            filter_tabs,
        )
        self.assertIn("MaterialStylePolicy.Module.LISTS", list_style)

        self.assertIn("if (usesFluxGramRootDialogsAppearance())", dialogs)
        self.assertIn("MaterialListStyle.filterChipGeometry(", dialogs)
        self.assertIn("filterTabsView.setBlurredBackground(filterTabsViewBackground)", dialogs)
        self.assertIn("MaterialListStyle.filterChipGeometry(", preview)
        self.assertIn("tabsView.setBlurredBackground(background)", preview)

        consumers = []
        for path in JAVA.rglob("*.java"):
            if path in (GEOMETRY, LIST_STYLE, FILTER_TABS):
                continue
            source = path.read_text()
            if "FilterChipGeometry" in source or "MaterialListStyle" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual(
            [
                "ui/Components/FluxGramFolderPreviewCell.java",
                "ui/Components/FluxGramPluginFilterCell.java",
                "ui/DialogsActivity.java",
                "ui/FluxGramSettingsActivity.java",
            ],
            sorted(consumers),
        )


if __name__ == "__main__":
    unittest.main()
