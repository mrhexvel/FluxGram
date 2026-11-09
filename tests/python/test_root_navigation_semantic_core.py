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
VIEW_PAGER = JAVA / "ui/ViewPagerActivity.java"


class RootNavigationSemanticCoreTest(unittest.TestCase):
    def test_snapshot_uses_immutable_effective_semantic_topology(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "RootNavigationSemanticCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.Arrays;
                import org.telegram.messenger.fluxgram.BottomNavigationSettings;
                import org.telegram.messenger.fluxgram.RootNavigationPolicy;
                import org.telegram.messenger.fluxgram.RootNavigationSnapshot;

                public final class RootNavigationSemanticCheck {
                    public static void main(String[] args) {
                        RootNavigationPolicy.State topology = RootNavigationPolicy.normalize(Arrays.asList(
                                RootNavigationPolicy.CONTACTS,
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS
                        ));
                        RootNavigationSnapshot visible = RootNavigationSnapshot.create(
                                2,
                                30,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationPolicy.CALLS
                        );

                        require(visible.getEffectiveOrder().equals(topology.getOrder()));
                        require(visible.hasDestination(RootNavigationPolicy.CALLS));
                        require(!visible.hasDestination(RootNavigationPolicy.PROFILE));
                        require(visible.canSelectInRoot(RootNavigationPolicy.SETTINGS));
                        require(RootNavigationSnapshot.isKnownDestination(RootNavigationPolicy.PROFILE));
                        require(!RootNavigationSnapshot.isKnownDestination("unknown"));
                        requireThrows(() -> visible.getEffectiveOrder().clear());

                        RootNavigationSnapshot hidden = RootNavigationSnapshot.create(
                                2,
                                30,
                                BottomNavigationSettings.HIDE,
                                topology,
                                RootNavigationPolicy.CALLS
                        );
                        require(hidden.getEffectiveOrder().equals(Arrays.asList(RootNavigationPolicy.CHATS)));
                        require(hidden.getActiveDestination().equals(RootNavigationPolicy.CHATS));
                        require(hidden.hasDestination(RootNavigationPolicy.CHATS));
                        require(!hidden.hasDestination(RootNavigationPolicy.CALLS));
                        require(!hidden.canSelectInRoot(RootNavigationPolicy.CONTACTS));

                        RootNavigationSnapshot copy = RootNavigationSnapshot.create(
                                2,
                                30,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationPolicy.CALLS
                        );
                        require(visible.equals(copy));
                        require(visible.hashCode() == copy.hashCode());
                        require(!visible.equals(RootNavigationSnapshot.create(
                                3,
                                30,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationPolicy.CALLS
                        )));
                        require(!visible.equals(RootNavigationSnapshot.create(
                                2,
                                31,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationPolicy.CALLS
                        )));
                        require(!visible.equals(RootNavigationSnapshot.create(
                                2,
                                30,
                                BottomNavigationSettings.SHOW,
                                topology,
                                RootNavigationPolicy.SETTINGS
                        )));
                        require(!visible.equals(hidden));
                        require(!visible.equals(RootNavigationSnapshot.create(
                                2,
                                30,
                                BottomNavigationSettings.SHOW,
                                RootNavigationPolicy.normalize(Arrays.asList(
                                        RootNavigationPolicy.CHATS,
                                        RootNavigationPolicy.CONTACTS,
                                        RootNavigationPolicy.CALLS,
                                        RootNavigationPolicy.SETTINGS
                                )),
                                RootNavigationPolicy.CALLS
                        )));
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
                [
                    "javac",
                    "--release",
                    "8",
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
                ["java", "-cp", str(root), "RootNavigationSemanticCheck"],
                check=True,
            )

    def test_view_pager_separates_semantic_key_from_raw_position(self):
        source = VIEW_PAGER.read_text()
        adapter = source.split("viewPager.setAdapter", 1)[1].split(
            "contentView.addView(viewPager", 1
        )[0]
        visibility = source.split("private void checkFragmentsVisibility()", 1)[1].split(
            "protected void dropFragmentAtPosition", 1
        )[0]

        self.assertIn("protected int getItemKey(int position)", source)
        self.assertIn("return getItemKey(position);", adapter)
        self.assertIn("fragmentsArr.get(itemKey)", adapter)
        self.assertIn("fragmentsArr.put(itemKey, state)", adapter)
        self.assertIn("state.position = position;", adapter)
        self.assertIn("getPositionVisibility(state.position)", visibility)
        self.assertNotIn("fragmentsArr.keyAt", visibility)
        self.assertIn(
            "fragmentsArr.put(getItemKey(position), new FragmentState(fragment, position))",
            source,
        )
        self.assertIn("fragmentsArr.get(getItemKey(currentPosition))", source)
        self.assertIn("final int itemKey = getItemKey(position);", source)

        snapshot = SNAPSHOT.read_text()
        for destination in ("CHATS", "CONTACTS", "CALLS", "SETTINGS", "PROFILE"):
            self.assertIn(
                f"public static final String {destination} = RootNavigationPolicy.{destination};",
                snapshot,
            )


if __name__ == "__main__":
    unittest.main()
