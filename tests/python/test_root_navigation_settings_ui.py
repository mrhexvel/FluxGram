from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
SETTINGS = JAVA / "ui/FluxGramSettingsActivity.java"
EDITOR = JAVA / "ui/FluxGramRootNavigationSettingsActivity.java"
CELL = JAVA / "ui/Components/FluxGramRootNavigationCell.java"
MAIN_TABS = JAVA / "ui/MainTabsActivity.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class RootNavigationSettingsUiTest(unittest.TestCase):
    def test_navigation_section_opens_dedicated_editor(self):
        source = SETTINGS.read_text()
        items = source.split("if (section == NAVIGATION) {", 1)[1].split(
            "PluginManager manager", 1
        )[0]
        clicks = source.split("if (section == NAVIGATION) {", 2)[2].split(
            "if (section == APPEARANCE) {", 1
        )[0]

        self.assertIn("ROOT_NAVIGATION,", items)
        self.assertIn("R.string.FluxGramRootNavigation", items)
        self.assertIn("new FluxGramRootNavigationSettingsActivity()", clicks)

    def test_editor_keeps_a_bounded_local_draft_until_confirmed_restart(self):
        source = EDITOR.read_text()
        click = source.split("private void onItemClick", 1)[1].split(
            "private void onReordered", 1
        )[0]
        reorder = source.split("private void onReordered", 1)[1].split(
            "private void moveItem", 1
        )[0]
        move = source.split("private void moveItem", 1)[1].split(
            "private void updateDoneItem", 1
        )[0]
        restart = source.split("private void restartWithOrder", 1)[1].split(
            "private CharSequence destinationTitle", 1
        )[0]

        self.assertIn("RootNavigationSettings.getState(", source)
        self.assertIn("getUserConfig().showCallsTab", source)
        self.assertIn("RootNavigationPolicy.CHATS.equals(destination)", click)
        self.assertIn("showRequiredDestination();", click)
        self.assertIn("activeOrder.size() >= RootNavigationPolicy.MAX_DESTINATIONS", click)
        self.assertIn("showLimitReached();", click)
        self.assertNotIn("RootNavigationSettings.setOrder", click)
        self.assertNotIn("RootNavigationSettings.setOrder", reorder)
        self.assertNotIn("RootNavigationSettings.setOrder", move)
        self.assertIn("RootNavigationSettings.setOrder(order)", restart)
        self.assertLess(
            restart.index("RootNavigationSettings.setOrder(order)"),
            restart.index("Intent.FLAG_ACTIVITY_NEW_TASK"),
        )
        self.assertIn("System.exit(0);", restart)
        self.assertIn("activeOrder.equals(initialOrder)", source)

    def test_only_active_rows_are_reorderable(self):
        source = EDITOR.read_text()
        fill = source.split("private void fillItems", 1)[1].split(
            "private void onItemClick", 1
        )[0]

        self.assertIn("UItem.asCustomShadow(label, LayoutHelper.WRAP_CONTENT)", fill)
        self.assertIn("orderSection = adapter.reorderSectionStart();", fill)
        self.assertIn("adapter.reorderSectionEnd();", fill)
        self.assertLess(
            fill.index("adapter.reorderSectionEnd();"),
            fill.index("for (String destination : RootNavigationPolicy.knownDestinations())"),
        )
        self.assertIn("!RootNavigationPolicy.CHATS.equals(destination)", fill)
        self.assertIn("listView.setReorderLongPressEnabled(false)", source)
        self.assertIn("listView.allowReorder(true)", source)

    def test_cell_has_handle_only_reorder_and_accessibility_moves(self):
        source = CELL.read_text()

        self.assertIn("reorderHandle.setOnTouchListener", source)
        self.assertIn("listView.startReorder(cell)", source)
        self.assertIn("setIcon(reorderEnabled ? R.drawable.list_reorder : 0)", source)
        self.assertIn("reorderHandle.setVisibility(reorderEnabled ? VISIBLE : INVISIBLE)", source)
        self.assertIn("ACTION_MOVE_UP", source)
        self.assertIn("ACTION_MOVE_DOWN", source)
        self.assertIn("info.setCheckable(false)", source)
        self.assertIn("getCheckBox().setEnabled(toggleEnabled)", source)

    def test_root_runtime_fixes_saved_topology_at_construction(self):
        source = MAIN_TABS.read_text()
        constructor = source.split("public MainTabsActivity()", 1)[1].split(
            "private static final class DockedNavigationDrawable", 1
        )[0]

        self.assertIn("RootNavigationSettings.getState(", constructor)
        self.assertIn("getUserConfig().showCallsTab", constructor)
        self.assertIn("private final RootNavigationPolicy.State rootNavigationState", source)
        self.assertEqual(source.count("RootNavigationSettings.getState("), 1)

    def test_resources_are_localized(self):
        english = STRINGS.read_text()
        russian = STRINGS_RU.read_text()

        for key in (
            "FluxGramRootNavigation",
            "FluxGramRootNavigationInfo",
            "FluxGramRootNavigationLimit",
            "FluxGramRootNavigationRequired",
            "FluxGramRootNavigationRestartMessage",
            "FluxGramRootNavigationRestartError",
        ):
            self.assertIn(f'name="{key}"', english)
            self.assertIn(f'name="{key}"', russian)


if __name__ == "__main__":
    unittest.main()
