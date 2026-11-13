from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
ACTIVITY = JAVA / "ui/FluxGramSettingsActivity.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class MaterialStyleUiTest(unittest.TestCase):
    def test_material_section_follows_interface_icons(self):
        source = ACTIVITY.read_text()
        fill_items = source.split("private void fillItems", 1)[1]
        appearance = fill_items.split("if (section == APPEARANCE) {", 1)[1].split(
            "if (section == CHATS) {", 1
        )[0]

        self.assertLess(
            appearance.index("addSectionLabel(items, R.string.FluxGramInterfaceIcons);"),
            appearance.index("addSectionLabel(items, R.string.FluxGramMaterialDesign3);"),
        )
        self.assertLess(
            appearance.index("addSectionLabel(items, R.string.FluxGramMaterialDesign3);"),
            appearance.index("addSectionLabel(items, R.string.FluxGramChatList);"),
        )
        self.assertIn("UItem.asSettingsCell(\n                    MATERIAL_STYLE_EXPAND", appearance)
        self.assertIn("if (materialStyleExpanded)", appearance)
        self.assertIn("R.drawable.arrow_more", appearance)
        self.assertIn("items.add(UItem.asShadow(null));", appearance)
        self.assertNotIn("UItem.asHeader", appearance)
        self.assertNotIn("MATERIAL_STYLE_PRESET", appearance)
        self.assertNotIn("materialStylePreview", appearance)

    def test_only_production_modules_are_listed(self):
        source = ACTIVITY.read_text()
        fill_items = source.split("private void fillItems", 1)[1]
        appearance = fill_items.split("if (section == APPEARANCE) {", 1)[1].split(
            "if (section == CHATS) {", 1
        )[0]

        supported = source.split(
            "private static final MaterialStylePolicy.Module[] MATERIAL_STYLE_MODULES = {", 1
        )[1].split("};", 1)[0]
        for module in (
            "MATERIAL_SYMBOLS",
            "NAVIGATION",
            "CONTROLS",
            "SURFACES",
            "LISTS",
            "PROGRESS",
            "PLAYER",
        ):
            self.assertIn(f"MaterialStylePolicy.Module.{module}", supported)
        for module in ("MOTION", "CHAT_CHROME", "MESSAGE_BUBBLES", "PROFILE"):
            self.assertNotIn(f"MaterialStylePolicy.Module.{module}", supported)

        self.assertIn("for (MaterialStylePolicy.Module module : MATERIAL_STYLE_MODULES)", appearance)
        self.assertIn("MaterialStylePolicy.resolve(materialStyleState)", appearance)
        self.assertIn("materialStyle.isEnabled(module)", appearance)
        self.assertIn(".setEnabled(true)", appearance)
        self.assertNotIn("DYNAMIC_COLORS", source)
        self.assertNotIn("resolveMaterialStyleAvailability", source)
        self.assertNotIn("Theme.getActiveTheme()", source)

    def test_preset_selection_is_not_exposed(self):
        source = ACTIVITY.read_text()
        self.assertNotIn("MATERIAL_STYLE_PRESET", source)
        self.assertNotIn("showMaterialStylePresetDialog", source)
        self.assertNotIn("materialStylePresetOption", source)
        self.assertNotIn("FluxGramMaterialPreset", source)

    def test_save_updates_only_material_rows_without_restart(self):
        source = ACTIVITY.read_text()
        apply_state = source.split("private void applyMaterialStyleState", 1)[1].split(
            "private int findItemPosition", 1
        )[0]

        self.assertIn("if (!MaterialStyleSettings.setState(state))", apply_state)
        self.assertLess(
            apply_state.index("MaterialStyleSettings.setState(state)"),
            apply_state.index("materialStyleState = state"),
        )
        self.assertIn("updateMaterialStyleRows(materialStyleExpanded", apply_state)
        self.assertIn("listView.stopScroll()", apply_state)
        self.assertIn("listView.adapter.updateWithoutNotify()", apply_state)
        self.assertIn("notifyItemRangeInserted", apply_state)
        self.assertIn("notifyItemRangeRemoved", apply_state)
        self.assertGreaterEqual(apply_state.count("expandPosition + 1"), 2)
        self.assertGreaterEqual(apply_state.count("MATERIAL_STYLE_MODULES.length"), 2)
        self.assertLess(
            apply_state.index("listView.adapter.updateWithoutNotify()"),
            apply_state.index("listView.adapter.notifyItemRangeInserted"),
        )
        self.assertLess(
            apply_state.index("listView.adapter.updateWithoutNotify()"),
            apply_state.index("listView.adapter.notifyItemRangeRemoved"),
        )
        self.assertNotIn("listView.adapter.update(true)", apply_state)
        self.assertNotIn("materialStylePreview", source)
        self.assertNotIn("confirmMaterialStyleRestart", source)
        self.assertNotIn("materialStyleRestartInProgress", source)

    def test_material_style_does_not_inspect_or_refresh_theme(self):
        source = ACTIVITY.read_text()
        apply_state = source.split("private void applyMaterialStyleState", 1)[1].split(
            "private void updateMaterialStyleRows", 1
        )[0]

        self.assertNotIn("Theme.getActiveTheme()", source)
        self.assertNotIn("Theme.refreshThemeColors()", apply_state)
        self.assertNotIn("DYNAMIC_COLORS", source)

    def test_resources_are_localized(self):
        english = STRINGS.read_text()
        russian = STRINGS_RU.read_text()
        keys = (
            "FluxGramMaterialDesign3",
            "FluxGramMaterialSymbols",
            "FluxGramMaterialNavigation",
            "FluxGramMaterialControls",
            "FluxGramMaterialSurfaces",
            "FluxGramMaterialLists",
            "FluxGramMaterialProgress",
            "FluxGramMaterialPlayer",
        )

        for key in keys:
            self.assertIn(f'name="{key}"', english)
            self.assertIn(f'name="{key}"', russian)

        self.assertNotIn('name="FluxGramMaterialPreset"', english)
        self.assertNotIn('name="FluxGramMaterialPreset"', russian)

    def test_production_material_state_consumers_are_bounded(self):
        consumers = []
        for path in JAVA.rglob("*.java"):
            if path.name in (
                "MaterialStylePolicy.java",
                "MaterialStyleSettings.java",
                "MaterialVisualStyle.java",
                "MaterialVisualStyleRuntime.java",
                "FluxGramSettingsHeaderCell.java",
                "FluxGramSettingsActivity.java",
            ):
                continue
            source = path.read_text()
            if "MaterialStylePolicy" in source or "MaterialStyleSettings" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual(
            [
                "messenger/ApplicationLoader.java",
                "messenger/fluxgram/icons/MaterialSymbolsCatalog.java",
                "ui/fluxgram/MaterialControlsStyle.java",
                "ui/fluxgram/MaterialListStyle.java",
                "ui/fluxgram/MaterialNavigationStyle.java",
                "ui/fluxgram/MaterialProgressStyle.java",
            ],
            sorted(consumers),
        )


if __name__ == "__main__":
    unittest.main()
