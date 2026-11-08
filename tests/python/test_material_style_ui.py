from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
ACTIVITY = JAVA / "ui/FluxGramSettingsActivity.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class MaterialStyleUiTest(unittest.TestCase):
    def test_material_section_precedes_existing_appearance_sections(self):
        source = ACTIVITY.read_text()
        fill_items = source.split("private void fillItems", 1)[1]
        appearance = fill_items.split("if (section == APPEARANCE) {", 1)[1].split(
            "if (section == CHATS) {", 1
        )[0]

        self.assertLess(
            appearance.index("addSectionLabel(items, R.string.FluxGramMaterialDesign3);"),
            appearance.index("addSectionLabel(items, R.string.FluxGramFontsAndEmoji);"),
        )
        self.assertIn("UItem.asButton(\n                        MATERIAL_STYLE_PRESET", appearance)
        self.assertIn("UItem.asSettingsCell(\n                    MATERIAL_STYLE_EXPAND", appearance)
        self.assertIn("if (materialStyleExpanded)", appearance)
        self.assertIn("R.drawable.arrow_more", appearance)
        self.assertIn("items.add(UItem.asShadow(materialInfo));", appearance)
        self.assertIn("materialStyleExpanded\n                    && materialStyleState.getPreset()", appearance)
        self.assertNotIn("UItem.asHeader", appearance)
        self.assertNotIn("materialStylePreview", appearance)

    def test_advanced_modules_are_conditional_and_availability_is_effective(self):
        source = ACTIVITY.read_text()
        fill_items = source.split("private void fillItems", 1)[1]
        appearance = fill_items.split("if (section == APPEARANCE) {", 1)[1].split(
            "if (section == CHATS) {", 1
        )[0]

        self.assertIn(
            "materialStyleState.getPreset() == MaterialStylePolicy.Preset.ADVANCED",
            appearance,
        )
        self.assertIn("for (MaterialStylePolicy.Module module", appearance)
        self.assertIn("materialStyleState.getCustomModules().contains(module)", appearance)
        self.assertIn("resolveMaterialStyleAvailability()", appearance)
        self.assertIn("boolean available = requested || materialAvailability", appearance)
        self.assertIn(".setEnabled(available)", appearance)
        self.assertIn("MaterialStylePolicy.UnavailableReason.NONE", appearance)
        self.assertIn("MaterialStylePolicy.UnavailableReason.PLATFORM", source)
        self.assertIn("MaterialStylePolicy.UnavailableReason.CUSTOM_THEME", source)
        self.assertIn("MaterialStylePolicy.create(", source)
        self.assertIn("EnumSet.allOf(MaterialStylePolicy.Module.class)", source)
        self.assertIn("if (requested || snapshot.getUnavailableReason(module)", source)

    def test_preset_uses_telegram_single_choice_dialog(self):
        source = ACTIVITY.read_text()
        dialog = source.split("private void showMaterialStylePresetDialog()", 1)[1].split(
            "private void applyMaterialStyleState", 1
        )[0]

        self.assertIn("AlertsCreator.createSingleChoiceDialog", dialog)
        self.assertIn("getResourceProvider()", dialog)
        self.assertIn("MaterialStylePolicy.Preset.TELEGRAM", dialog)
        self.assertIn("MaterialStylePolicy.Preset.COLORS_AND_ICONS", dialog)
        self.assertIn("MaterialStylePolicy.Preset.BALANCED", dialog)
        self.assertIn("MaterialStylePolicy.Preset.ADVANCED", dialog)

    def test_save_updates_only_material_rows_without_restart(self):
        source = ACTIVITY.read_text()
        apply_state = source.split("private void applyMaterialStyleState", 1)[1].split(
            "private MaterialStylePolicy.EffectiveSnapshot resolveMaterialStyle", 1
        )[0]

        self.assertIn("if (!MaterialStyleSettings.setState(state))", apply_state)
        self.assertLess(
            apply_state.index("MaterialStyleSettings.setState(state)"),
            apply_state.index("materialStyleState = state"),
        )
        self.assertIn("updateMaterialStyleRows(previousState", apply_state)
        self.assertIn("listView.stopScroll()", apply_state)
        self.assertIn("listView.adapter.updateWithoutNotify()", apply_state)
        self.assertIn("notifyItemRangeInserted", apply_state)
        self.assertIn("notifyItemRangeRemoved", apply_state)
        self.assertNotIn("listView.adapter.update(true)", apply_state)
        self.assertNotIn("materialStylePreview", source)
        self.assertNotIn("confirmMaterialStyleRestart", source)
        self.assertNotIn("materialStyleRestartInProgress", source)

    def test_custom_theme_detection_uses_only_file_path(self):
        source = ACTIVITY.read_text()
        resolver = source.split("private MaterialStylePolicy.EffectiveSnapshot resolveMaterialStyle", 1)[1].split(
            "private String materialStylePresetOption", 1
        )[0]

        self.assertIn("Theme.getActiveTheme()", resolver)
        self.assertIn("!TextUtils.isEmpty(theme.pathToFile)", resolver)
        self.assertNotIn("assetName", resolver)
        self.assertIn("Build.VERSION.SDK_INT", resolver)

    def test_resources_are_localized(self):
        english = STRINGS.read_text()
        russian = STRINGS_RU.read_text()
        keys = (
            "FluxGramMaterialDesign3",
            "FluxGramMaterialPreset",
            "FluxGramMaterialPresetAdvanced",
            "FluxGramMaterialDynamicColors",
            "FluxGramMaterialDynamicColorsPlatformInfo",
            "FluxGramMaterialDynamicColorsThemeInfo",
        )

        for key in keys:
            self.assertIn(f'name="{key}"', english)
            self.assertIn(f'name="{key}"', russian)

    def test_no_production_theme_or_renderer_reads_material_state(self):
        consumers = []
        for path in JAVA.rglob("*.java"):
            if path.name in (
                "MaterialStylePolicy.java",
                "MaterialStyleSettings.java",
                "FluxGramSettingsActivity.java",
            ):
                continue
            source = path.read_text()
            if "MaterialStylePolicy" in source or "MaterialStyleSettings" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual([], consumers)


if __name__ == "__main__":
    unittest.main()
