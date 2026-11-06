from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
CATALOG = JAVA / "ui/fluxgram/FolderIconCatalog.java"


class FolderSettingsTest(unittest.TestCase):
    def test_folder_icon_catalog_runtime_contract(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        catalog_source = CATALOG.read_text()
        drawables = sorted(set(re.findall(r"R\.drawable\.(filter_[a-z_]+)", catalog_source)))
        self.assertEqual(30, len(drawables))

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            messenger = root / "org/telegram/messenger"
            check_package = root / "org/telegram/ui/fluxgram"
            messenger.mkdir(parents=True)
            check_package.mkdir(parents=True)

            (messenger / "MessagesController.java").write_text(
                """
package org.telegram.messenger;

public final class MessagesController {
    public static final int DIALOG_FILTER_FLAG_CONTACTS = 0x01;
    public static final int DIALOG_FILTER_FLAG_NON_CONTACTS = 0x02;
    public static final int DIALOG_FILTER_FLAG_GROUPS = 0x04;
    public static final int DIALOG_FILTER_FLAG_CHANNELS = 0x08;
    public static final int DIALOG_FILTER_FLAG_BOTS = 0x10;
    public static final int DIALOG_FILTER_FLAG_EXCLUDE_MUTED = 0x20;
    public static final int DIALOG_FILTER_FLAG_EXCLUDE_READ = 0x40;
    public static final int DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED = 0x80;
    public static final int DIALOG_FILTER_FLAG_ALL_CHATS = 0x1f;
}
""".strip()
            )
            drawable_fields = "\n".join(
                f"        public static final int {name} = {index + 1};"
                for index, name in enumerate(drawables)
            )
            (messenger / "R.java").write_text(
                f"""
package org.telegram.messenger;

public final class R {{
    public static final class drawable {{
{drawable_fields}
    }}
}}
""".strip()
            )
            check = check_package / "FolderIconCatalogCheck.java"
            check.write_text(
                """
package org.telegram.ui.fluxgram;

import java.util.HashSet;
import org.telegram.messenger.MessagesController;

public final class FolderIconCatalogCheck {
    public static void main(String[] args) {
        if (FolderIconCatalog.size() != 30) throw new AssertionError();
        if (!"✈".equals(FolderIconCatalog.normalize("✈️"))) throw new AssertionError();
        if (FolderIconCatalog.resolve("unknown") != 0) throw new AssertionError();

        HashSet<String> emoticons = new HashSet<>();
        HashSet<Integer> drawables = new HashSet<>();
        for (int index = 0; index < FolderIconCatalog.size(); index++) {
            if (!emoticons.add(FolderIconCatalog.getEmoticon(index))) throw new AssertionError();
            if (!drawables.add(FolderIconCatalog.getDrawable(index))) throw new AssertionError();
            if (FolderIconCatalog.resolve(FolderIconCatalog.getEmoticon(index))
                    != FolderIconCatalog.getDrawable(index)) throw new AssertionError();
        }

        if (!"👤".equals(FolderIconCatalog.resolveForFlags(
                MessagesController.DIALOG_FILTER_FLAG_CONTACTS))) throw new AssertionError();
        if (!"✅".equals(FolderIconCatalog.resolveForFlags(
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ))) throw new AssertionError();
        if (FolderIconCatalog.resolveForFlags(
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED) != null) {
            throw new AssertionError();
        }
        if (!FolderIconCatalog.DEFAULT_EMOTICON.equals(FolderIconCatalog.resolveForFlags(
                MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ
                        | MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED))) {
            throw new AssertionError();
        }
    }
}
""".strip()
            )

            subprocess.run(
                [
                    "javac",
                    "--release",
                    "8",
                    "-d",
                    str(root),
                    str(messenger / "MessagesController.java"),
                    str(messenger / "R.java"),
                    str(CATALOG),
                    str(check),
                ],
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "org.telegram.ui.fluxgram.FolderIconCatalogCheck"],
                check=True,
                capture_output=True,
            )

    def test_policy_is_owner_scoped_and_defaults_to_name_and_icon(self):
        source = (JAVA / "messenger/fluxgram/FolderSettings.java").read_text()

        self.assertIn("public static final class Policy", source)
        self.assertIn("private final int presentationMode;", source)
        self.assertIn("private final int counterMode;", source)
        self.assertIn("private final boolean showAllChats;", source)
        self.assertIn('"fluxgram_folders_" + account', source)
        self.assertIn("preferences.getLong(OWNER, 0) != owner", source)
        self.assertIn("? MODE_TITLE_AND_ICON", source)
        self.assertIn("? COUNTER_ALL", source)
        self.assertIn("validatePresentationMode(mode)", source)
        self.assertIn("validateCounterMode(counterMode)", source)
        self.assertIn("preferences.getBoolean(SHOW_ALL_CHATS, true)", source)

    def test_semantic_icons_use_folder_catalog_assets(self):
        source = (JAVA / "ui/fluxgram/FolderPresentation.java").read_text()

        for icon in (
            "filter_custom",
            "filter_private",
            "filter_group",
            "filter_channels",
            "filter_bots",
            "filter_unmuted",
            "filter_unread",
            "msg_folders_archive",
        ):
            self.assertIn(f"R.drawable.{icon}", source)
        self.assertIn("DIALOG_FILTER_FLAG_ALL_CHATS", source)
        self.assertIn("ColoredImageSpan.ALIGN_CENTER", source)

    def test_selected_folder_icons_override_semantic_fallback(self):
        source = (JAVA / "ui/fluxgram/FolderPresentation.java").read_text()
        catalog = CATALOG.read_text()

        self.assertIn("resolveIcon(filter.flags, filter.emoticon, filter.isDefault())", source)
        self.assertIn("FolderIconCatalog.resolve(emoticon)", source)
        self.assertIn("FolderIconCatalog.containsDrawable(iconDrawable) ? 22 : 18", source)
        self.assertEqual(30, catalog.count("R.drawable.filter_"))
        self.assertIn("public static boolean containsDrawable", catalog)
        self.assertIn("public static String resolveForFlags", catalog)
        self.assertIn("DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED", catalog)
        for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi"):
            path = ROOT / f"TMessagesProj/src/main/res/drawable-{density}"
            self.assertEqual(30, len(list(path.glob("filter_*.png"))))

    def test_folder_editor_exposes_the_icon_selector(self):
        editor = (JAVA / "ui/FilterCreateActivity.java").read_text()
        selector = (JAVA / "ui/fluxgram/FolderIconSelector.java").read_text()
        icon_block = editor.split("folderIconView = new ImageView", 1)[1].split(
            "AnimatedEmojiDrawable.toggleAnimations", 1
        )[0]

        self.assertIn("newFilterEmoticon = filter.emoticon", editor)
        self.assertIn("FolderIconSelector.show", editor)
        self.assertIn("filter.emoticon = newFilterEmoticon", editor)
        self.assertIn("TextUtils.equals(filter.emoticon, newFilterEmoticon)", editor)
        self.assertIn("GridLayout", selector)
        self.assertIn("FolderIconCatalog.size()", selector)
        self.assertIn("LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT", selector)
        self.assertNotIn("anchor.getX()", selector)
        self.assertIn("protected void onDraw(Canvas canvas)", selector)
        self.assertIn("Gravity.CENTER_VERTICAL | Gravity.LEFT", icon_block)
        self.assertIn("dp(56),\n                            editText.getPaddingTop()", editor)
        self.assertNotIn(
            "Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)",
            icon_block,
        )

    def test_selected_folder_icon_is_persisted(self):
        controller = (JAVA / "messenger/MessagesController.java").read_text()
        storage = (JAVA / "messenger/MessagesStorage.java").read_text()
        migration = (JAVA / "messenger/DatabaseMigrationHelper.java").read_text()
        editor = (JAVA / "ui/FilterCreateActivity.java").read_text()
        setup = (JAVA / "ui/FiltersSetupActivity.java").read_text()

        self.assertIn("public String emoticon;", controller)
        self.assertIn("public boolean emoticonLoaded = true", controller)
        self.assertIn("LAST_DB_VERSION = 182", storage)
        self.assertIn("emoticon TEXT, emoticon_loaded INTEGER DEFAULT 1", storage)
        self.assertIn("emoticon, emoticon_loaded FROM dialog_filter", storage)
        self.assertIn("filter.emoticon = filtersCursor.stringValue(8)", storage)
        self.assertIn("filter.emoticonLoaded = filtersCursor.intValue(9) == 1", storage)
        self.assertIn("filter.emoticon = newFilter.emoticon", storage)
        self.assertIn("filter.emoticonLoaded = true", storage)
        self.assertIn("ALTER TABLE dialog_filter ADD COLUMN emoticon TEXT", migration)
        self.assertIn("PRAGMA user_version = 180", migration)
        self.assertIn("ADD COLUMN emoticon_loaded INTEGER DEFAULT 0", migration)
        self.assertIn("PRAGMA user_version = 182", migration)
        self.assertNotIn("userConfig.filtersLoaded = false", migration)
        self.assertIn("loadRemoteFilters(forceEmoticonRefresh)", controller)
        self.assertIn("if (!creatingNew && !filter.emoticonLoaded)", editor)
        self.assertIn("req.filter.emoticon = newFilterEmoticon", editor)
        self.assertIn("req.filter.flags |= 33554432", editor)
        self.assertIn("filter.emoticon = suggested.filter.emoticon", setup)

    def test_existing_folder_icon_is_not_replaced_while_editing_title(self):
        editor = (JAVA / "ui/FilterCreateActivity.java").read_text()

        self.assertIn("if (creatingNew && !folderIconChangedManually)", editor)
        self.assertIn("folderIconChangedManually || creatingNew", editor)
        self.assertIn("!folderIconChangedManually && filter.emoticonLoaded", editor)
        self.assertIn("newFilterEmoticon = filter.emoticon", editor)
        self.assertIn("folderIconChanged ? newFilterEmoticon : refreshedFilter.emoticon", editor)

    def test_legacy_icon_refresh_uses_immutable_save_snapshots(self):
        editor = (JAVA / "ui/FilterCreateActivity.java").read_text()

        self.assertIn("new ArrayList<>(newAlwaysShow)", editor)
        self.assertIn("new ArrayList<>(newNeverShow)", editor)
        self.assertIn("newPinned.clone()", editor)
        self.assertIn("if (saving)", editor)
        self.assertIn("createErrorBulletin(LocaleController.getString(R.string.UnknownError))", editor)

    def test_forced_remote_refresh_is_not_lost_behind_an_active_load(self):
        controller = (JAVA / "messenger/MessagesController.java").read_text()
        completion = controller.split("private void completeRemoteFiltersLoad", 1)[1].split(
            "public void selectDialogFilter", 1
        )[0]

        self.assertIn("forceRemoteFiltersAfterCurrent", controller)
        self.assertIn("onForcedRemoteFilters", controller)
        self.assertIn("loadRemoteFilters(true);", controller)
        self.assertIn("onForcedRemoteFilters.clear();", controller)
        self.assertLess(
            completion.index("loadingRemoteFiltersForced = false;"),
            completion.index("callbacks.get(a).run(success);"),
        )

    def test_remote_filter_callbacks_do_not_survive_account_cleanup(self):
        controller = (JAVA / "messenger/MessagesController.java").read_text()

        self.assertIn("onLoadedRemoteFilters.clear();", controller)
        self.assertIn("onForcedRemoteFilters.clear();", controller)

    def test_folder_icon_assets_have_attribution(self):
        license_text = (ROOT / "TMessagesProj/src/main/FOLDER_ICONS_LICENSE.md").read_text()

        self.assertIn("exteraSquad/exteraGram", license_text)
        self.assertIn("6f78031aafd7f27e0dfaa31a207715569a6df1a2", license_text)
        self.assertIn("Nekogram", license_text)
        self.assertIn("@immat0x1", license_text)
        self.assertIn("GNU General Public License version 2", license_text)

    def test_root_tabs_use_neutral_default_title_hook(self):
        tabs = (JAVA / "ui/Components/FilterTabsView.java").read_text()
        dialogs = (JAVA / "ui/DialogsActivity.java").read_text()

        self.assertNotIn("FolderSettings", tabs)
        self.assertNotIn("FolderPresentation", tabs)
        self.assertEqual(5, tabs.count("getDefaultTabTitle("))
        self.assertIn("protected CharSequence getDefaultTabTitle", tabs)
        self.assertIn("if (!usesFluxGramRootDialogsAppearance())", dialogs)
        self.assertIn("FolderPresentation.createTitle", dialogs)
        self.assertIn(
            "filterTabsView.addTab(a, filter.localId, filter.name, filter.entities",
            dialogs,
        )

    def test_main_tabs_selector_uses_same_policy_and_resolver(self):
        source = (JAVA / "ui/MainTabsActivity.java").read_text()

        self.assertIn("FolderSettings.getPolicy(currentAccount)", source)
        self.assertIn("FolderPresentation.resolveIcon(folder)", source)
        self.assertIn("folderItem.setContentDescription", source)
        self.assertIn("folderPolicy.getPresentationMode() == FolderSettings.MODE_ICON", source)
        self.assertIn("folderPolicy.getPresentationMode() == FolderSettings.MODE_TITLE", source)
        self.assertIn("FolderUnreadCounter.resolve(currentAccount, folder, folderPolicy)", source)
        self.assertIn("FolderSettings.isAllChatsVisible(folderPolicy, filters)", source)

    def test_counter_modes_use_storage_backed_aggregates(self):
        settings = (JAVA / "messenger/fluxgram/FolderSettings.java").read_text()
        storage = (JAVA / "messenger/MessagesStorage.java").read_text()
        resolver = (JAVA / "ui/fluxgram/FolderUnreadCounter.java").read_text()
        dialogs = (JAVA / "ui/DialogsActivity.java").read_text()

        for mode in ("COUNTER_ALL", "COUNTER_UNMUTED", "COUNTER_HIDDEN"):
            self.assertIn(mode, settings)
        self.assertIn("counterStride * 3", storage)
        self.assertIn("pendingMainAllUnreadCount", storage)
        self.assertIn("pendingMainUnmutedUnreadCount", storage)
        self.assertIn("pendingUnmutedUnreadCount", storage)
        self.assertIn("getMainAllUnreadCount()", resolver)
        self.assertIn("getMainUnmutedUnreadCount()", resolver)
        self.assertIn("filter.unmutedUnreadCount", resolver)
        self.assertIn("FolderUnreadCounter.resolve", dialogs)

    def test_counter_setting_rebuilds_existing_surfaces(self):
        source = (JAVA / "ui/FluxGramSettingsActivity.java").read_text()

        self.assertIn("FOLDER_COUNTER", source)
        self.assertIn("showFolderCounterDialog()", source)
        self.assertIn("FolderSettings.setCounterMode(currentAccount, index)", source)
        self.assertIn("folderPreview.updateFolders(true);", source)
        self.assertIn("AndroidUtilities.runOnUIThread(rebuildFolderFragments, 250);", source)

    def test_preview_uses_the_real_folder_tabs_component(self):
        preview = (JAVA / "ui/Components/FluxGramFolderPreviewCell.java").read_text()
        settings = (JAVA / "ui/FluxGramSettingsActivity.java").read_text()

        self.assertIn("new FilterTabsView(context, resourcesProvider)", preview)
        self.assertIn("FolderPresentation.createTitle", preview)
        self.assertIn("FolderUnreadCounter.resolve", preview)
        self.assertIn("MessagesController.getInstance(account).getDialogFilters()", preview)
        self.assertIn("NotificationCenter.dialogFiltersUpdated", preview)
        self.assertIn("BlurredBackgroundProviderImpl.topPanel(resourcesProvider)", preview)
        self.assertIn("background.setRadius(dp(18));", preview)
        self.assertIn("background.setPadding(dp(6.666f));", preview)
        self.assertIn("tabsView.refreshTabs(animated);", preview)
        self.assertIn("items.add(UItem.asCustomShadow(folderPreview, 58));", settings)

    def test_all_chats_visibility_uses_an_unlocked_fallback(self):
        policy = (JAVA / "messenger/fluxgram/FolderSettings.java").read_text()
        dialogs = (JAVA / "ui/DialogsActivity.java").read_text()
        tabs = (JAVA / "ui/Components/FilterTabsView.java").read_text()
        preview = (JAVA / "ui/Components/FluxGramFolderPreviewCell.java").read_text()
        settings = (JAVA / "ui/FluxGramSettingsActivity.java").read_text()

        self.assertIn("!filter.isDefault() && !filter.locked", policy)
        self.assertIn("policy.showsAllChats() || findFallbackFilter(filters) < 0", policy)
        self.assertIn("FolderSettings.setShowsAllChats(currentAccount, shown)", settings)
        self.assertIn("FolderSettings.findFallbackFilter(filters)", dialogs)
        self.assertIn("if (!showAllChats)", dialogs)
        self.assertIn("viewPages[a].selectedType = fallbackFilter;", dialogs)
        self.assertIn("filterTabsView.getTabPosition(index)", dialogs)
        self.assertIn("public int getTabPosition(int id)", tabs)
        self.assertIn("MessagesController.DialogFilter filter1 = filters.get(tab1.id);", tabs)
        self.assertIn("FolderSettings.isAllChatsVisible(policy, filters)", preview)


if __name__ == "__main__":
    unittest.main()
