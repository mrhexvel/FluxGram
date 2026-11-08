from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
POLICY = JAVA / "messenger/fluxgram/MaterialStylePolicy.java"
SETTINGS = JAVA / "messenger/fluxgram/MaterialStyleSettings.java"


class MaterialStylePolicyTest(unittest.TestCase):
    def test_policy_presets_codec_and_availability(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            check = root / "MaterialStylePolicyCheck.java"
            check.write_text(textwrap.dedent("""
                import java.util.Arrays;
                import java.util.Collections;
                import java.util.Set;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.EffectiveSnapshot;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Module;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Preset;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.State;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.UnavailableReason;

                public final class MaterialStylePolicyCheck {
                    public static void main(String[] args) {
                        checkPresets();
                        checkCustomState();
                        checkAvailability();
                        checkCodec();
                        checkImmutability();
                    }

                    private static void checkPresets() {
                        EffectiveSnapshot telegram = MaterialStylePolicy.resolve(
                                MaterialStylePolicy.defaults(),
                                36,
                                false
                        );
                        require(telegram.getPreset() == Preset.TELEGRAM);
                        require(telegram.getRequestedModules().isEmpty());
                        require(telegram.getEffectiveModules().isEmpty());

                        State colors = MaterialStylePolicy.selectPreset(
                                MaterialStylePolicy.defaults(),
                                Preset.COLORS_AND_ICONS
                        );
                        EffectiveSnapshot colorsSnapshot = MaterialStylePolicy.resolve(colors, 36, false);
                        require(colorsSnapshot.getRequestedModules().equals(setOf(
                                Module.DYNAMIC_COLORS,
                                Module.MATERIAL_SYMBOLS
                        )));

                        State balanced = MaterialStylePolicy.selectPreset(colors, Preset.BALANCED);
                        EffectiveSnapshot balancedSnapshot = MaterialStylePolicy.resolve(balanced, 36, false);
                        require(balancedSnapshot.getRequestedModules().equals(setOf(
                                Module.DYNAMIC_COLORS,
                                Module.MATERIAL_SYMBOLS,
                                Module.NAVIGATION,
                                Module.CONTROLS,
                                Module.SURFACES,
                                Module.LISTS,
                                Module.PROGRESS
                        )));
                        require(!balancedSnapshot.isRequested(Module.MOTION));
                        require(!balancedSnapshot.isRequested(Module.CHAT_CHROME));
                    }

                    private static void checkCustomState() {
                        State custom = MaterialStylePolicy.create(
                                Preset.ADVANCED,
                                setOf(Module.MOTION, Module.PROFILE)
                        );
                        State temporaryPreset = MaterialStylePolicy.selectPreset(custom, Preset.BALANCED);
                        require(temporaryPreset.getCustomModules().equals(custom.getCustomModules()));

                        State restored = MaterialStylePolicy.selectPreset(temporaryPreset, Preset.ADVANCED);
                        EffectiveSnapshot restoredSnapshot = MaterialStylePolicy.resolve(restored, 36, false);
                        require(restoredSnapshot.getRequestedModules().equals(setOf(
                                Module.MOTION,
                                Module.PROFILE
                        )));

                        State colors = MaterialStylePolicy.selectPreset(custom, Preset.COLORS_AND_ICONS);
                        State edited = MaterialStylePolicy.setModuleEnabled(
                                colors,
                                Module.MATERIAL_SYMBOLS,
                                false
                        );
                        require(edited.getPreset() == Preset.ADVANCED);
                        require(edited.getCustomModules().equals(setOf(Module.DYNAMIC_COLORS)));

                        State nullModule = MaterialStylePolicy.setModuleEnabled(colors, null, true);
                        require(nullModule.equals(colors));
                    }

                    private static void checkAvailability() {
                        State colors = MaterialStylePolicy.selectPreset(
                                MaterialStylePolicy.defaults(),
                                Preset.COLORS_AND_ICONS
                        );
                        EffectiveSnapshot oldPlatform = MaterialStylePolicy.resolve(colors, 30, true);
                        require(oldPlatform.isRequested(Module.DYNAMIC_COLORS));
                        require(!oldPlatform.isEnabled(Module.DYNAMIC_COLORS));
                        require(oldPlatform.isEnabled(Module.MATERIAL_SYMBOLS));
                        require(oldPlatform.getUnavailableReason(Module.DYNAMIC_COLORS)
                                == UnavailableReason.PLATFORM);

                        EffectiveSnapshot customTheme = MaterialStylePolicy.resolve(colors, 31, true);
                        require(customTheme.isRequested(Module.DYNAMIC_COLORS));
                        require(!customTheme.isEnabled(Module.DYNAMIC_COLORS));
                        require(customTheme.getUnavailableReason(Module.DYNAMIC_COLORS)
                                == UnavailableReason.CUSTOM_THEME);

                        EffectiveSnapshot available = MaterialStylePolicy.resolve(colors, 31, false);
                        require(available.isEnabled(Module.DYNAMIC_COLORS));
                        require(available.getUnavailableReason(Module.DYNAMIC_COLORS)
                                == UnavailableReason.NONE);
                    }

                    private static void checkCodec() {
                        State state = MaterialStylePolicy.create(
                                Preset.ADVANCED,
                                setOf(Module.DYNAMIC_COLORS, Module.MESSAGE_BUBBLES, Module.PROFILE)
                        );
                        String encoded = MaterialStylePolicy.encode(state);
                        require(MaterialStylePolicy.decode(encoded).equals(state));

                        State preset = MaterialStylePolicy.selectPreset(state, Preset.TELEGRAM);
                        State decodedPreset = MaterialStylePolicy.decode(MaterialStylePolicy.encode(preset));
                        require(decodedPreset.equals(preset));
                        require(decodedPreset.getCustomModules().equals(state.getCustomModules()));

                        String[] invalid = {
                                "",
                                "2:3:0",
                                "1:4:0",
                                "1:3:800",
                                "1:3:-1",
                                "1:3:0:extra",
                                "1:03:0",
                                "1:3:A",
                                "junk"
                        };

                        require(MaterialStylePolicy.decode(null).equals(MaterialStylePolicy.defaults()));
                        for (String value : invalid) {
                            State fallback = MaterialStylePolicy.decode(value);
                            require(fallback.equals(MaterialStylePolicy.defaults()));
                            require(MaterialStylePolicy.resolve(fallback, 36, false)
                                    .getEffectiveModules().isEmpty());
                        }
                    }

                    private static void checkImmutability() {
                        java.util.EnumSet<Module> input = java.util.EnumSet.of(Module.CONTROLS);
                        State state = MaterialStylePolicy.create(
                                Preset.ADVANCED,
                                input
                        );
                        input.add(Module.PROFILE);
                        EffectiveSnapshot snapshot = MaterialStylePolicy.resolve(state, 36, false);

                        require(state.getCustomModules().equals(setOf(Module.CONTROLS)));
                        requireThrows(() -> state.getCustomModules().add(Module.LISTS));
                        requireThrows(() -> snapshot.getRequestedModules().clear());
                        requireThrows(() -> snapshot.getEffectiveModules().add(Module.PROFILE));
                    }

                    private static Set<Module> setOf(Module... modules) {
                        return modules.length == 0
                                ? Collections.emptySet()
                                : new java.util.LinkedHashSet<>(Arrays.asList(modules));
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
                ["javac", "-d", str(root), str(POLICY), str(check)],
                check=True,
            )
            subprocess.run(
                ["java", "-cp", str(root), "MaterialStylePolicyCheck"],
                check=True,
            )

    def test_policy_is_android_independent_and_consumers_are_limited_to_material_foundation(self):
        source = POLICY.read_text()

        self.assertNotIn("android.", source)
        self.assertNotIn("org.telegram.ui", source)
        self.assertNotIn("SharedPreferences", source)

        consumers = []
        for path in JAVA.rglob("*.java"):
            if path in (POLICY, SETTINGS):
                continue
            source = path.read_text()
            if "MaterialStylePolicy" in source or "MaterialStyleSettings" in source:
                consumers.append(path.relative_to(JAVA).as_posix())

        self.assertEqual(
            [
                "messenger/fluxgram/MaterialVisualStyle.java",
                "messenger/fluxgram/MaterialVisualStyleRuntime.java",
                "ui/Components/FluxGramSettingsHeaderCell.java",
                "ui/FluxGramSettingsActivity.java",
            ],
            sorted(consumers),
        )

    def test_storage_is_single_process_global_value(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("java and javac are required")

        source = SETTINGS.read_text()

        self.assertIn('"fluxgram_material_style_state"', source)
        self.assertGreaterEqual(source.count("MessagesController.getGlobalMainSettings()"), 3)
        self.assertIn("MaterialStylePolicy.decode(encoded)", source)
        self.assertIn("MaterialStylePolicy.encode(state)", source)
        self.assertIn(".commit();", source)
        self.assertNotIn("UserConfig", source)
        self.assertNotIn("org.telegram.ui", source)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shared_preferences = root / "android/content/SharedPreferences.java"
            messages_controller = root / "org/telegram/messenger/MessagesController.java"
            check = root / "MaterialStyleSettingsCheck.java"
            shared_preferences.parent.mkdir(parents=True)
            messages_controller.parent.mkdir(parents=True)
            shared_preferences.write_text(textwrap.dedent("""
                package android.content;

                public interface SharedPreferences {
                    String getString(String key, String defaultValue);
                    Editor edit();

                    interface Editor {
                        Editor putString(String key, String value);
                        Editor remove(String key);
                        boolean commit();
                        void apply();
                    }
                }
            """))
            messages_controller.write_text(textwrap.dedent("""
                package org.telegram.messenger;

                import android.content.SharedPreferences;

                public final class MessagesController {
                    private static SharedPreferences preferences;

                    public static SharedPreferences getGlobalMainSettings() {
                        return preferences;
                    }

                    public static void setGlobalMainSettingsForTests(SharedPreferences value) {
                        preferences = value;
                    }
                }
            """))
            check.write_text(textwrap.dedent("""
                import android.content.SharedPreferences;
                import java.util.HashMap;
                import java.util.Map;
                import org.telegram.messenger.MessagesController;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Module;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.Preset;
                import org.telegram.messenger.fluxgram.MaterialStylePolicy.State;
                import org.telegram.messenger.fluxgram.MaterialStyleSettings;

                public final class MaterialStyleSettingsCheck {
                    private static final String KEY = "fluxgram_material_style_state";

                    public static void main(String[] args) {
                        FakePreferences preferences = new FakePreferences();
                        MessagesController.setGlobalMainSettingsForTests(preferences);

                        preferences.values.put(KEY, "2:3:0");
                        State fallback = MaterialStyleSettings.getState();
                        require(fallback.equals(MaterialStylePolicy.defaults()));
                        require(preferences.commits == 0);
                        require(preferences.applies == 0);
                        require("2:3:0".equals(preferences.values.get(KEY)));

                        preferences.values.put(KEY, Integer.valueOf(7));
                        fallback = MaterialStyleSettings.getState();
                        require(fallback.equals(MaterialStylePolicy.defaults()));
                        require(preferences.commits == 0);
                        require(preferences.values.get(KEY).equals(Integer.valueOf(7)));

                        State custom = MaterialStylePolicy.create(
                                Preset.ADVANCED,
                                java.util.Collections.singleton(Module.PROFILE)
                        );
                        require(MaterialStyleSettings.setState(custom));
                        require(preferences.commits == 1);
                        require(preferences.applies == 0);
                        require(preferences.values.size() == 1);
                        require(MaterialStyleSettings.getState().equals(custom));

                        require(MaterialStyleSettings.reset());
                        require(preferences.commits == 2);
                        require(preferences.applies == 0);
                        require(preferences.values.isEmpty());
                    }

                    private static void require(boolean value) {
                        if (!value) {
                            throw new AssertionError();
                        }
                    }

                    private static final class FakePreferences implements SharedPreferences {
                        private final Map<String, Object> values = new HashMap<>();
                        private int commits;
                        private int applies;

                        @Override
                        public String getString(String key, String defaultValue) {
                            Object value = values.get(key);
                            if (value == null) {
                                return defaultValue;
                            }
                            if (!(value instanceof String)) {
                                throw new ClassCastException();
                            }

                            return (String) value;
                        }

                        @Override
                        public Editor edit() {
                            return new FakeEditor();
                        }

                        private final class FakeEditor implements Editor {
                            private String key;
                            private String value;
                            private boolean remove;

                            @Override
                            public Editor putString(String key, String value) {
                                this.key = key;
                                this.value = value;
                                remove = false;
                                return this;
                            }

                            @Override
                            public Editor remove(String key) {
                                this.key = key;
                                value = null;
                                remove = true;
                                return this;
                            }

                            @Override
                            public boolean commit() {
                                commits++;
                                publish();
                                return true;
                            }

                            @Override
                            public void apply() {
                                applies++;
                                publish();
                            }

                            private void publish() {
                                if (remove) {
                                    values.remove(key);
                                } else {
                                    values.put(key, value);
                                }
                            }
                        }
                    }
                }
            """))

            subprocess.run(
                [
                    "javac",
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
                ["java", "-cp", str(root), "MaterialStyleSettingsCheck"],
                check=True,
            )


if __name__ == "__main__":
    unittest.main()
