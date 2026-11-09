from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
BOTTOM_NAVIGATION = JAVA / "messenger/fluxgram/BottomNavigationSettings.java"
POLICY = JAVA / "messenger/fluxgram/RootNavigationPolicy.java"
SNAPSHOT = JAVA / "messenger/fluxgram/RootNavigationSnapshot.java"


class RootNavigationContractTest(unittest.TestCase):
    def test_snapshot_captures_topology_and_destination_rules(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "RootNavigationSnapshotCheck.java"
            check.write_text(textwrap.dedent("""
                import org.telegram.messenger.fluxgram.BottomNavigationSettings;
                import org.telegram.messenger.fluxgram.RootNavigationPolicy;
                import org.telegram.messenger.fluxgram.RootNavigationSnapshot;

                public final class RootNavigationSnapshotCheck {
                    public static void main(String[] args) {
                        RootNavigationPolicy.State topology = RootNavigationPolicy.normalize(
                                java.util.Arrays.asList(
                                        RootNavigationPolicy.PROFILE,
                                        RootNavigationPolicy.CHATS,
                                        RootNavigationPolicy.CALLS,
                                        RootNavigationPolicy.SETTINGS
                                )
                        );
                        RootNavigationSnapshot settings = RootNavigationSnapshot.create(
                                1,
                                20,
                                BottomNavigationSettings.FLOATING,
                                topology,
                                RootNavigationSnapshot.SETTINGS
                        );
                        require(settings.getAccount() == 1);
                        require(settings.getRootIdentity() == 20);
                        require(settings.isBottomNavigationVisible());
                        require(settings.getActiveDestination().equals(RootNavigationSnapshot.SETTINGS));
                        require(!settings.canSelectInRoot(RootNavigationSnapshot.CONTACTS));
                        require(settings.canSelectInRoot(RootNavigationSnapshot.SETTINGS));
                        require(settings.canSelectInRoot(RootNavigationSnapshot.CALLS));
                        require(settings.hasDestination(RootNavigationSnapshot.CALLS));
                        require(!settings.hasDestination(RootNavigationSnapshot.CONTACTS));
                        require(RootNavigationSnapshot.isKnownDestination(RootNavigationSnapshot.CONTACTS));

                        RootNavigationSnapshot calls = RootNavigationSnapshot.create(
                                1,
                                20,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationSnapshot.CALLS
                        );
                        require(calls.canSelectInRoot(RootNavigationSnapshot.CALLS));
                        require(calls.canSelectInRoot(RootNavigationSnapshot.SETTINGS));
                        require(!settings.matches(calls));

                        RootNavigationSnapshot hidden = RootNavigationSnapshot.create(
                                1,
                                20,
                                BottomNavigationSettings.HIDE,
                                topology,
                                RootNavigationSnapshot.CONTACTS
                        );
                        require(!hidden.isBottomNavigationVisible());
                        require(hidden.getActiveDestination().equals(RootNavigationSnapshot.CHATS));
                        require(hidden.canSelectInRoot(RootNavigationSnapshot.CHATS));
                        require(!hidden.canSelectInRoot(RootNavigationSnapshot.CONTACTS));

                        RootNavigationSnapshot copy = RootNavigationSnapshot.create(
                                1,
                                20,
                                BottomNavigationSettings.HIDE,
                                topology,
                                RootNavigationSnapshot.CONTACTS
                        );
                        require(hidden.matches(copy));
                        require(hidden.hashCode() == copy.hashCode());
                        require(!hidden.matches(RootNavigationSnapshot.create(
                                1,
                                21,
                                BottomNavigationSettings.HIDE,
                                topology,
                                RootNavigationSnapshot.CHATS
                        )));
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
                    str(BOTTOM_NAVIGATION),
                    str(POLICY),
                    str(SNAPSHOT),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "RootNavigationSnapshotCheck"],
                check=True,
            )

    def test_controller_exposes_snapshot_bound_semantic_navigation(self):
        controller = (JAVA / "ui/MainTabsActivityController.java").read_text()

        self.assertIn("RootNavigationSnapshot getRootNavigationSnapshot()", controller)
        self.assertIn("navigateToRootDestination", controller)
        self.assertIn("RootNavigationSnapshot snapshot", controller)
        self.assertNotIn("int position", controller)

    def test_main_tabs_rejects_stale_snapshots_and_owns_raw_positions(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        navigation = activity.split("private boolean navigateToRootDestination", 1)[1].split(
            "private int positionForDestination", 1
        )[0]

        self.assertIn("!currentSnapshot.matches(snapshot)", navigation)
        self.assertIn("currentSnapshot == null", navigation)
        self.assertIn("!RootNavigationSnapshot.isKnownDestination(destination)", navigation)
        self.assertIn("currentSnapshot.canSelectInRoot(destination)", navigation)
        self.assertIn("viewPager.scrollToPosition(positionForDestination(destination))", navigation)

    def test_main_tabs_builds_pages_from_fixed_semantic_topology(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        create_view = activity.split("public View createView(Context context)", 1)[1].split(
            "private GlassTabView createTab", 1
        )[0]
        fragments = activity.split("protected BaseFragment createBaseFragmentAt", 1)[1].split(
            "public DialogsActivity getDialogsActivity", 1
        )[0]

        self.assertIn("private final RootNavigationPolicy.State rootNavigationState", activity)
        self.assertEqual(activity.count("RootNavigationSettings.getState("), 1)
        self.assertIn("position < rootNavigationState.size()", create_view)
        self.assertIn("rootNavigationState.getOrder().get(position)", create_view)
        self.assertIn("createTab(context, destination)", create_view)
        self.assertIn("return rootNavigationState.size();", activity)
        self.assertIn("protected int getItemKey(int position)", activity)
        self.assertIn("itemKeyForDestination(destinationForPosition(position))", activity)
        self.assertIn("RootNavigationPolicy.CALLS.equals(destination)", fragments)
        self.assertIn("RootNavigationPolicy.SETTINGS.equals(destination)", fragments)
        self.assertNotIn("POSITION_CALLS_OR_SETTINGS", activity)
        self.assertNotIn("callTabsVisibleToggled", activity)

        colors = activity.split("private void blur3_updateColors()", 1)[1].split(
            "public EdgeToEdgeSupportMode", 1
        )[0]
        self.assertIn("if (tabView != null)", colors)

    def test_legacy_calls_actions_do_not_mutate_fixed_topology(self):
        calls = (JAVA / "ui/CallLogActivity.java").read_text()

        self.assertNotIn("ID_SHOW_IN_MAIN_TABS", calls)
        self.assertNotIn("setCallsTabVisible", calls)
        self.assertNotIn("NotificationCenter.callTabsVisibleToggled", calls)
        self.assertNotIn("TapToHideCallsTab", calls)

    def test_standalone_fallback_reuses_fragment_routing(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        navigation = activity.split("private boolean navigateToRootDestination", 1)[1].split(
            "private int positionForDestination", 1
        )[0]
        standalone = activity.split("private BaseFragment createStandaloneDestination", 1)[1].split(
            "public GlassTabView[] tabs", 1
        )[0]

        self.assertIn("getParentLayout().checkTransitionAnimation()", navigation)
        self.assertIn("boolean presented = presentFragment(fragment);", navigation)
        self.assertIn("presented || fragment.getParentLayout() != null", navigation)
        self.assertIn('args.putBoolean("hasMainTabs", false)', standalone)
        self.assertIn("new ContactsActivity(args)", standalone)
        self.assertIn("new CallLogActivity(args)", standalone)
        self.assertIn("new SettingsActivity(args)", standalone)
        self.assertIn("new ProfileActivity(args)", standalone)

    def test_snapshot_is_fail_closed_during_detach(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        snapshot = activity.split("private RootNavigationSnapshot getRootNavigationSnapshot", 1)[1].split(
            "private String destinationForPosition", 1
        )[0]

        self.assertIn("fragmentView == null", snapshot)
        self.assertIn("!fragmentView.isAttachedToWindow()", snapshot)
        self.assertIn("viewPager == null", snapshot)
        self.assertIn("getParentLayout() == null", snapshot)
        self.assertIn("rootNavigationState", snapshot)

    def test_root_pager_keeps_full_size_pages_during_horizontal_transition(self):
        pager = (JAVA / "ui/ViewPagerActivity.java").read_text()
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()

        self.assertNotIn("onViewPagerPageTransform", pager)
        self.assertNotIn("onViewPagerPageTransform", activity)
        self.assertNotIn("applyRootPageTransform", activity)
        self.assertIn("return 240L;", pager)

    def test_root_pager_retains_a_container_for_each_destination(self):
        pager = (JAVA / "ui/ViewPagerActivity.java").read_text()
        adapter = pager.split("viewPager.setAdapter", 1)[1].split(
            "contentView.addView(viewPager", 1
        )[0]

        self.assertIn("public int getItemViewType(int position)", adapter)
        self.assertIn("return getItemKey(position);", adapter)
        self.assertIn("fragmentsArr.get(itemKey)", adapter)
        self.assertIn("fragmentsArr.put(itemKey, state)", adapter)
        self.assertIn("if (fragmentView.getParent() != container)", adapter)
        self.assertEqual(adapter.count("container.removeAllViews();"), 1)

    def test_profile_tab_is_retained_after_page_transition(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        scroll_end = activity.split("protected void onViewPagerScrollEnd()", 1)[1].split(
            "protected void onViewPagerTabAnimationUpdate", 1
        )[0]

        self.assertNotIn("dropFragmentAtPosition", scroll_end)

    def test_retained_root_profile_skips_redundant_full_resume_layout(self):
        profile = (JAVA / "ui/ProfileActivity.java").read_text()
        resume = profile.split("public void onResume()", 1)[1].split(
            "public void onPause()", 1
        )[0]

        self.assertIn("boolean needsFullResumeLayout = !hasMainTabs || !fragmentOpened;", resume)
        self.assertIn("listAdapter != null && needsFullResumeLayout", resume)
        self.assertIn("if (needsFullResumeLayout) {\n            fixLayout();", resume)
        self.assertIn("updateProfileData(true);", resume)
        self.assertIn("if (needsFullResumeLayout) {\n            needLayout(false);", resume)

    def test_root_lists_refresh_after_the_page_transition(self):
        contacts = (JAVA / "ui/ContactsActivity.java").read_text()
        settings = (JAVA / "ui/SettingsActivity.java").read_text()

        contacts_resume = contacts.split("public void onResume()", 1)[1].split(
            "public void onBecomeFullyVisible()", 1
        )[0]
        contacts_visible = contacts.split("public void onBecomeFullyVisible()", 1)[1].split(
            "protected RecyclerListView getListView()", 1
        )[0]
        settings_resume = settings.split("public void onResume()", 1)[1].split(
            "public void onBecomeFullyVisible()", 1
        )[0]
        settings_visible = settings.split("public void onBecomeFullyVisible()", 1)[1].split(
            "private ArrayList<Integer> accountNumbers", 1
        )[0]

        self.assertIn("!hasMainTabs && listViewAdapter != null", contacts_resume)
        self.assertIn("hasMainTabs && listViewAdapter != null", contacts_visible)
        self.assertIn("!hasMainTabs && listView.adapter != null", settings_resume)
        self.assertIn("hasMainTabs && listView.adapter != null", settings_visible)

    def test_settings_blur_sources_are_recorded_only_when_invalidated(self):
        settings = (JAVA / "ui/SettingsActivity.java").read_text()
        simple_glass = (JAVA / "utils/glass/BaseFragmentSimpleGlass.java").read_text()
        draw = settings.split("protected void dispatchDraw(Canvas canvas)", 1)[1].split(
            "super.dispatchDraw(canvas)", 1
        )[0]

        self.assertIn("updateAvatarHide();", draw)
        self.assertIn("glassEngine.invalidate();", settings)
        self.assertIn("glassEngine.setGlassInvalidationListener(this::invalidate)", simple_glass)
        self.assertIn("glassCaptureSourceCanvas.invalidate();", simple_glass)
        self.assertIn("return getBaseSimpleGlass().getGlassSource();", settings)
        self.assertIn("return getBaseSimpleGlass().getFrostedGlassSource();", settings)

    def test_root_glass_capture_follows_only_page_translation(self):
        activity = (JAVA / "ui/MainTabsActivity.java").read_text()
        glass = activity.split("private GlassSourceRenderNode createTabSource", 1)[1].split(
            "protected FrameLayout createContentView", 1
        )[0]

        self.assertIn("canvas.translate(fragmentPosition.left, fragmentPosition.top)", glass)
        self.assertNotIn("rootPage.getAlpha()", glass)
        self.assertNotIn("rootPage.getScaleX()", glass)
        self.assertNotIn("rootPage.getScaleY()", glass)
        self.assertNotIn("canvas.saveLayerAlpha", glass)

    def test_root_popup_uses_policy_and_snapshot_navigation(self):
        dialogs = (JAVA / "ui/DialogsActivity.java").read_text()
        popup = dialogs.split("private void showItemOptions()", 1)[1].split(
            "final boolean isCurrentThemeDark", 1
        )[0]
        policy = dialogs.split("private void addRootMainMenuOptions", 1)[1].split(
            "private boolean isRootMainMenuContributorAnchor", 1
        )[0]
        command = dialogs.split("private void addRootMainMenuCommand", 1)[1].split(
            "private void addRootMainMenuTheme", 1
        )[0]

        self.assertIn("usesFluxGramRootDialogsAppearance()", popup)
        self.assertIn("getRootNavigationSnapshot()", popup)
        self.assertIn("snapshot.getAccount() != currentAccount", popup)
        self.assertIn("RootNavigationSnapshot.CHATS.equals(snapshot.getActiveDestination())", popup)
        self.assertIn("addRootMainMenuOptions", popup)
        self.assertIn("MainMenuPolicy.resolve", policy)
        self.assertIn("MainMenuSettings.getState()", policy)
        self.assertIn("snapshot.canSelectInRoot(RootNavigationSnapshot.SETTINGS)", policy)
        self.assertIn("snapshot.canSelectInRoot(RootNavigationSnapshot.PROFILE)", policy)
        self.assertIn("controller.navigateToRootDestination(snapshot, RootNavigationSnapshot.CONTACTS)", command)
        self.assertIn("controller.navigateToRootDestination(snapshot, RootNavigationSnapshot.CALLS)", command)
        self.assertIn("controller.navigateToRootDestination(snapshot, RootNavigationSnapshot.SETTINGS)", command)
        self.assertNotIn("presentFragment(new SettingsActivity", command)

    def test_root_popup_preserves_dynamic_contributor_slot(self):
        dialogs = (JAVA / "ui/DialogsActivity.java").read_text()
        policy = dialogs.split("private void addRootMainMenuOptions", 1)[1].split(
            "private boolean isRootMainMenuContributorAnchor", 1
        )[0]
        anchor = dialogs.split("private boolean isRootMainMenuContributorAnchor", 1)[1].split(
            "private void addRootMainMenuCommand", 1
        )[0]
        contributors = dialogs.split("private void addRootMainMenuContributors", 1)[1].split(
            "private boolean isRootMainMenuProxyAvailable", 1
        )[0]

        self.assertIn("isRootMainMenuContributorAnchor(id)", policy)
        self.assertIn("addRootMainMenuContributors", policy)
        self.assertIn("MainMenuPolicy.WALLET.equals(id)", anchor)
        self.assertIn("applicationLoaderInstance.addItemOptions(io)", contributors)
        self.assertIn("if (walletAvailable)", contributors)
        self.assertIn("io.addBot", contributors)
        self.assertIn("BotWebViewSheet.deleteBot", contributors)


if __name__ == "__main__":
    unittest.main()
