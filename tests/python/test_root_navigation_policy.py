from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
POLICY = JAVA / "messenger/fluxgram/RootNavigationPolicy.java"
SETTINGS = JAVA / "messenger/fluxgram/RootNavigationSettings.java"
SNAPSHOT = JAVA / "messenger/fluxgram/RootNavigationSnapshot.java"
BOTTOM_NAVIGATION = JAVA / "messenger/fluxgram/BottomNavigationSettings.java"


class RootNavigationPolicyTest(unittest.TestCase):
    def test_policy_normalizes_and_decodes_topology(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "RootNavigationPolicyCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.Arrays;
                import java.util.HashSet;
                import org.telegram.messenger.fluxgram.RootNavigationPolicy;
                import org.telegram.messenger.fluxgram.RootNavigationSnapshot;

                public final class RootNavigationPolicyCheck {
                    public static void main(String[] args) {
                        checkCatalog();
                        checkLegacyDefaults();
                        checkNormalization();
                        checkCodec();
                        checkImmutability();
                    }

                    private static void checkCatalog() {
                        require(RootNavigationPolicy.knownDestinations().size() == 5);
                        require(new HashSet<>(RootNavigationPolicy.knownDestinations()).size() == 5);
                        require(RootNavigationPolicy.CHATS.equals(RootNavigationSnapshot.CHATS));
                        require(RootNavigationPolicy.CONTACTS.equals(RootNavigationSnapshot.CONTACTS));
                        require(RootNavigationPolicy.CALLS.equals(RootNavigationSnapshot.CALLS));
                        require(RootNavigationPolicy.SETTINGS.equals(RootNavigationSnapshot.SETTINGS));
                        require(RootNavigationPolicy.PROFILE.equals(RootNavigationSnapshot.PROFILE));
                    }

                    private static void checkLegacyDefaults() {
                        RootNavigationPolicy.State settings = RootNavigationPolicy.legacyDefault(false);
                        require(settings.getOrder().equals(Arrays.asList(
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CONTACTS,
                                RootNavigationPolicy.SETTINGS,
                                RootNavigationPolicy.PROFILE
                        )));

                        RootNavigationPolicy.State calls = RootNavigationPolicy.legacyDefault(true);
                        require(calls.getOrder().equals(Arrays.asList(
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CONTACTS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.PROFILE
                        )));
                    }

                    private static void checkNormalization() {
                        RootNavigationPolicy.State empty = RootNavigationPolicy.normalize(null);
                        require(empty.getOrder().equals(Arrays.asList(RootNavigationPolicy.CHATS)));

                        RootNavigationPolicy.State state = RootNavigationPolicy.normalize(Arrays.asList(
                                RootNavigationPolicy.CONTACTS,
                                "unknown",
                                RootNavigationPolicy.CONTACTS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS,
                                RootNavigationPolicy.PROFILE,
                                RootNavigationPolicy.CHATS
                        ));
                        require(state.getOrder().equals(Arrays.asList(
                                RootNavigationPolicy.CONTACTS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS,
                                RootNavigationPolicy.CHATS
                        )));
                        require(state.size() == RootNavigationPolicy.MAX_DESTINATIONS);
                        require(state.contains(RootNavigationPolicy.CALLS));
                        require(state.contains(RootNavigationPolicy.SETTINGS));
                        require(state.positionOf(RootNavigationPolicy.SETTINGS) == 2);
                        require(!state.hasAccountsDestination());
                        require(state.hasSettingsDestination());
                        require(state.resolveActiveDestination(RootNavigationPolicy.PROFILE)
                                .equals(RootNavigationPolicy.CHATS));
                        require(state.resolveActiveDestination(RootNavigationPolicy.CALLS)
                                .equals(RootNavigationPolicy.CALLS));

                        String[] bounded = new String[65];
                        Arrays.fill(bounded, "unknown");
                        bounded[0] = RootNavigationPolicy.CHATS;
                        bounded[63] = RootNavigationPolicy.CONTACTS;
                        require(RootNavigationPolicy.normalize(Arrays.asList(bounded))
                                .getOrder().equals(Arrays.asList(
                                        RootNavigationPolicy.CHATS,
                                        RootNavigationPolicy.CONTACTS
                                )));

                        Arrays.fill(bounded, "unknown");
                        bounded[0] = RootNavigationPolicy.CHATS;
                        bounded[64] = RootNavigationPolicy.CONTACTS;
                        require(RootNavigationPolicy.normalize(Arrays.asList(bounded))
                                .getOrder().equals(Arrays.asList(
                                        RootNavigationPolicy.CHATS
                                )));
                    }

                    private static void checkCodec() {
                        RootNavigationPolicy.State state = RootNavigationPolicy.normalize(Arrays.asList(
                                RootNavigationPolicy.PROFILE,
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS
                        ));
                        String encoded = RootNavigationPolicy.encode(state);
                        require(encoded.equals("1:profile,chats,calls,settings"));
                        require(RootNavigationPolicy.decode(encoded, false).equals(state));

                        require(RootNavigationPolicy.decode(null, false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationPolicy.decode("2:chats", true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));
                        require(RootNavigationPolicy.decode("1:", false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationPolicy.decode("1:contacts", true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));
                        require(RootNavigationPolicy.decode("1:chats,chats", false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationPolicy.decode("1:chats,unknown", true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));
                        require(RootNavigationPolicy.decode(
                                "1:chats,contacts,calls,settings,profile", false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationPolicy.decode("1:chats:", true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));
                    }

                    private static void checkImmutability() {
                        RootNavigationPolicy.State state = RootNavigationPolicy.normalize(Arrays.asList(
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.PROFILE
                        ));
                        requireThrows(() -> state.getOrder().add(RootNavigationPolicy.CALLS));
                        requireThrows(() -> RootNavigationPolicy.knownDestinations().clear());
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
                    str(SNAPSHOT),
                    str(POLICY),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "RootNavigationPolicyCheck"],
                check=True,
            )

    def test_settings_use_synchronous_versioned_storage(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shared_preferences = root / "android/content/SharedPreferences.java"
            messages_controller = root / "org/telegram/messenger/MessagesController.java"
            check = root / "RootNavigationSettingsCheck.java"
            shared_preferences.parent.mkdir(parents=True)
            messages_controller.parent.mkdir(parents=True)

            shared_preferences.write_text(textwrap.dedent("""
                package android.content;

                public interface SharedPreferences {
                    boolean contains(String key);
                    String getString(String key, String fallback);
                    Editor edit();

                    interface Editor {
                        Editor putString(String key, String value);
                        Editor remove(String key);
                        boolean commit();
                    }
                }
            """))
            messages_controller.write_text(textwrap.dedent("""
                package org.telegram.messenger;

                import android.content.SharedPreferences;
                import java.util.HashMap;
                import java.util.HashSet;
                import java.util.Map;
                import java.util.Set;

                public final class MessagesController {
                    public static final MemoryPreferences preferences = new MemoryPreferences();

                    public static SharedPreferences getGlobalMainSettings() {
                        return preferences;
                    }

                    public static final class MemoryPreferences implements SharedPreferences {
                        private final Map<String, Object> values = new HashMap<>();
                        public boolean commitSucceeds = true;

                        public boolean contains(String key) {
                            return values.containsKey(key);
                        }

                        public String getString(String key, String fallback) {
                            return values.containsKey(key) ? (String) values.get(key) : fallback;
                        }

                        public void putRaw(String key, Object value) {
                            values.put(key, value);
                        }

                        public Editor edit() {
                            return new MemoryEditor();
                        }

                        private final class MemoryEditor implements Editor {
                            private final Map<String, String> writes = new HashMap<>();
                            private final Set<String> removals = new HashSet<>();

                            public Editor putString(String key, String value) {
                                writes.put(key, value);
                                removals.remove(key);
                                return this;
                            }

                            public Editor remove(String key) {
                                removals.add(key);
                                writes.remove(key);
                                return this;
                            }

                            public boolean commit() {
                                for (String key : removals) {
                                    values.remove(key);
                                }
                                values.putAll(writes);
                                return commitSucceeds;
                            }
                        }
                    }
                }
            """))
            check.write_text(textwrap.dedent("""
                import java.util.Arrays;
                import org.telegram.messenger.MessagesController;
                import org.telegram.messenger.fluxgram.RootNavigationPolicy;
                import org.telegram.messenger.fluxgram.RootNavigationSettings;

                public final class RootNavigationSettingsCheck {
                    public static void main(String[] args) {
                        require(!RootNavigationSettings.hasExplicitState());
                        require(RootNavigationSettings.getState(false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationSettings.getState(true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));

                        require(RootNavigationSettings.setOrder(Arrays.asList(
                                RootNavigationPolicy.PROFILE,
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS
                        )));
                        require(RootNavigationSettings.hasExplicitState());
                        require(RootNavigationSettings.getState(false).getOrder().equals(Arrays.asList(
                                RootNavigationPolicy.PROFILE,
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CALLS,
                                RootNavigationPolicy.SETTINGS
                        )));
                        require(RootNavigationSettings.getState(true)
                                .equals(RootNavigationSettings.getState(false)));

                        MessagesController.preferences.putRaw(
                                "fluxgram_root_navigation_topology",
                                "2:chats"
                        );
                        require(RootNavigationSettings.getState(false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(RootNavigationSettings.getState(true)
                                .equals(RootNavigationPolicy.legacyDefault(true)));
                        require(!RootNavigationSettings.hasExplicitState());

                        MessagesController.preferences.putRaw(
                                "fluxgram_root_navigation_topology",
                                Integer.valueOf(7)
                        );
                        require(RootNavigationSettings.getState(false)
                                .equals(RootNavigationPolicy.legacyDefault(false)));
                        require(!RootNavigationSettings.hasExplicitState());

                        require(RootNavigationSettings.reset());
                        require(!RootNavigationSettings.hasExplicitState());

                        MessagesController.preferences.commitSucceeds = false;
                        require(!RootNavigationSettings.setOrder(Arrays.asList(
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CONTACTS
                        )));
                        require(RootNavigationSettings.hasExplicitState());
                        require(RootNavigationSettings.getState(false).getOrder().equals(Arrays.asList(
                                RootNavigationPolicy.CHATS,
                                RootNavigationPolicy.CONTACTS
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
                    "--release",
                    "8",
                    "-d",
                    str(root),
                    str(shared_preferences),
                    str(messages_controller),
                    str(POLICY),
                    str(SETTINGS),
                    str(check),
                ],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "RootNavigationSettingsCheck"],
                check=True,
            )

    def test_new_state_is_not_consumed_by_root_runtime(self):
        consumers = []
        for path in JAVA.rglob("*.java"):
            if path in (POLICY, SETTINGS):
                continue
            source = path.read_text()
            if "RootNavigationPolicy" in source or "RootNavigationSettings" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual(
            [
                "ui/FluxGramRootNavigationSettingsActivity.java",
                "ui/FluxGramSettingsActivity.java",
            ],
            sorted(consumers),
        )


if __name__ == "__main__":
    unittest.main()
