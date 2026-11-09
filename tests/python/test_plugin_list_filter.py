from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
FILTER = JAVA / "messenger/fluxgram/PluginListFilter.java"
ACTIVITY = JAVA / "ui/FluxGramSettingsActivity.java"
CELL = JAVA / "ui/Components/FluxGramPluginFilterCell.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class PluginListFilterTest(unittest.TestCase):
    def test_filter_catalog_normalization_and_matching(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "PluginListFilterCheck.java"
            check.write_text(textwrap.dedent("""
                import org.telegram.messenger.fluxgram.PluginListFilter;
                import org.telegram.messenger.fluxgram.PluginListFilter.Filter;

                public final class PluginListFilterCheck {
                    public static void main(String[] args) {
                        require(Filter.values().length == 4);
                        require(Filter.values()[0] == Filter.ALL);
                        require(Filter.values()[1] == Filter.ENABLED);
                        require(Filter.values()[2] == Filter.DISABLED);
                        require(Filter.values()[3] == Filter.ATTENTION);

                        require(PluginListFilter.normalize(null, 4) == Filter.ALL);
                        require(PluginListFilter.normalize(Filter.ATTENTION, 0) == Filter.ALL);
                        require(PluginListFilter.normalize(Filter.ENABLED, 1) == Filter.ALL);
                        require(PluginListFilter.normalize(Filter.DISABLED, 2) == Filter.DISABLED);

                        require(PluginListFilter.matches(null, false, false));
                        require(PluginListFilter.matches(Filter.ALL, true, true));
                        require(PluginListFilter.matches(Filter.ENABLED, true, false));
                        require(!PluginListFilter.matches(Filter.ENABLED, false, true));
                        require(PluginListFilter.matches(Filter.DISABLED, false, false));
                        require(!PluginListFilter.matches(Filter.DISABLED, true, true));
                        require(PluginListFilter.matches(Filter.ATTENTION, true, true));
                        require(PluginListFilter.matches(Filter.ATTENTION, false, true));
                        require(!PluginListFilter.matches(Filter.ATTENTION, false, false));
                    }

                    private static void require(boolean value) {
                        if (!value) {
                            throw new AssertionError();
                        }
                    }
                }
            """))

            subprocess.run(
                ["javac", "-d", str(root), str(FILTER), str(check)],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "PluginListFilterCheck"],
                check=True,
            )

    def test_plugin_screen_uses_local_external_only_filter_chips(self):
        filter_source = FILTER.read_text()
        activity = ACTIVITY.read_text()
        cell = CELL.read_text()
        plugin_section = activity.split(
            "PluginManager manager = PluginManager.getInstance();", 1
        )[1].split("private void showPluginActions", 1)[0]

        self.assertNotIn("android.", filter_source)
        self.assertNotIn("SharedPreferences", filter_source)
        self.assertLess(
            plugin_section.index("items.add(UItem.asRippleCheck(\n                PLUGIN_SYSTEM"),
            plugin_section.index("addPluginItems(items, manager, true, true)"),
        )
        self.assertIn("if (!systemEnabled) {\n            return;", plugin_section)
        self.assertIn("plugins.size() >= 2", plugin_section)
        self.assertIn("external && !PluginListFilter.matches", plugin_section)
        self.assertIn("UItem.asCustomShadow(pluginFilterCell, 48)", plugin_section)
        self.assertIn("State.CHECKING", plugin_section)
        self.assertIn('"FAILED".equals(state)', plugin_section)
        self.assertIn('"REMOVAL_PENDING".equals(state)', plugin_section)
        self.assertIn("AndroidUtilities.runOnUIThread(refresh)", plugin_section)

        built_in_branch = plugin_section.split("if (section == BUILT_IN_PLUGINS)", 1)[1].split(
            "CharSequence systemDescription", 1
        )[0]
        self.assertNotIn("pluginListFilter", built_in_branch)
        self.assertNotIn("FluxGramPluginFilterCell", built_in_branch)

        self.assertNotIn("MaterialStylePolicy", cell)
        self.assertNotIn("MaterialVisualStyle", cell)
        self.assertIn("FilterChipGeometry geometry", cell)
        self.assertIn("setMarginStart", cell)
        self.assertIn('info.setClassName("android.widget.RadioButton")', cell)
        self.assertIn("info.setCheckable(true)", cell)
        self.assertIn("info.setChecked(checked)", cell)
        self.assertIn("checkedProgress = checked ? 1.0f : 0.0f", cell)

    def test_filter_labels_are_localized(self):
        english = STRINGS.read_text()
        russian = STRINGS_RU.read_text()
        keys = (
            "FluxGramPluginFilterAll",
            "FluxGramPluginFilterEnabled",
            "FluxGramPluginFilterDisabled",
            "FluxGramPluginFilterAttention",
        )

        for key in keys:
            self.assertIn(f'name="{key}"', english)
            self.assertIn(f'name="{key}"', russian)


if __name__ == "__main__":
    unittest.main()
