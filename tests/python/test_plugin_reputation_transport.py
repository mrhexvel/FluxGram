import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNING = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
REPUTATION = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation"


class PluginReputationTransportTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-reputation-transport-") as output:
            sources = [
                SIGNING / "PluginSignedPayload.java",
                SIGNING / "PluginSignatureEnvelope.java",
                SIGNING / "PluginSignatureVerification.java",
                SIGNING / "PluginSignatureVerifier.java",
                SIGNING / "PluginSignerIdentity.java",
                SIGNING / "PluginKeyRotationProof.java",
                REPUTATION / "PluginReputationResponse.java",
                REPUTATION / "PluginReputationVerification.java",
                REPUTATION / "PluginReputationVerifier.java",
                REPUTATION / "PluginReputationQuery.java",
                REPUTATION / "PluginReputationTransportResponse.java",
                REPUTATION / "PluginReputationEndpoint.java",
                REPUTATION / "PluginReputationHttpTransport.java",
                ROOT / "tests/java/PluginReputationTransportCheck.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    "java",
                    "-cp",
                    output,
                    "org.telegram.messenger.plugins.reputation.PluginReputationTransportCheck",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

    def test_configuration_contract(self):
        config = (ROOT / "gradle/fluxgram-config.gradle").read_text(encoding="utf-8")
        example = (ROOT / "fluxgram.local.properties.example").read_text(encoding="utf-8")

        self.assertIn("FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL", config)
        self.assertIn("FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID", config)
        self.assertIn("fluxgram.plugin_reputation_api_base_url", config)
        self.assertIn("fluxgram.plugin_reputation_authority_key_id", config)
        self.assertIn("buildConfigField 'String', 'FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL'", config)
        self.assertIn("buildConfigField 'String', 'FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID'", config)
        self.assertIn("pluginReputationApiBaseUrl.isEmpty() != pluginReputationAuthorityKeyId.isEmpty()", config)
        self.assertIn("fluxgram.plugin_reputation_api_base_url=", example)
        self.assertIn("fluxgram.plugin_reputation_authority_key_id=", example)

    def test_gradle_configuration_matrix(self):
        java_home = self._java_home()

        with tempfile.TemporaryDirectory(prefix="plugin-reputation-config-") as temporary:
            project = Path(temporary)
            config = ROOT / "gradle/fluxgram-config.gradle"
            build = project / "build.gradle"
            settings = project / "settings.gradle"
            build.write_text(
                f"""
class FakeDefaultConfig {{
    final Map<String, String> fields = [:]

    void buildConfigField(String type, String name, String value) {{
        fields[name] = value
    }}
}}

class FakeAndroid {{
    final FakeDefaultConfig defaultConfig = new FakeDefaultConfig()

    void defaultConfig(Closure closure) {{
        closure.delegate = defaultConfig
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
    }}
}}

ext.android = new FakeAndroid()
tasks.register('preBuild')
apply from: '{config.as_posix()}'

tasks.register('verifyConfig') {{
    doLast {{
        def selected = [
                'FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL',
                'FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID'
        ]
        def output = file("$buildDir/generated-config.txt")
        output.parentFile.mkdirs()
        output.text = selected.collect {{ name -> name + '=' + android.defaultConfig.fields[name] }}.join('\\n')
    }}
}}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            settings.write_text("rootProject.name = 'config-check'\n", encoding="utf-8")

            empty = self._run_gradle_config(project, java_home, "", "")
            self.assertEqual(0, empty.returncode, "Empty reputation configuration must load")
            self.assertEqual(
                {
                    'FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL=""',
                    'FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID=""',
                },
                set((project / "build/generated-config.txt").read_text(encoding="utf-8").splitlines()),
            )

            authority = "p256:" + "a" * 64
            valid = self._run_gradle_config(
                project,
                java_home,
                "https://reputation.fluxgram.org",
                authority,
            )
            self.assertFalse(
                "Plugin reputation base URL and authority key ID" in valid.stdout + valid.stderr,
                "Valid reputation pair rejected by validator",
            )
            self.assertEqual(0, valid.returncode, "Valid reputation configuration must load")
            self.assertEqual(
                {
                    'FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL="https://reputation.fluxgram.org"',
                    f'FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID="{authority}"',
                },
                set((project / "build/generated-config.txt").read_text(encoding="utf-8").splitlines()),
            )

            invalid = (
                ("https://reputation.fluxgram.org", ""),
                ("", authority),
                ("http://reputation.fluxgram.org", authority),
                ("https://reputation.fluxgram.org/path", authority),
                ("https://reputation.fluxgram.org:444", authority),
                ("https://localhost", authority),
                ("https://127.0.0.1", authority),
                ("https://reputation.fluxgram.org", "p256:" + "A" * 64),
            )

            for base_url, key_id in invalid:
                with self.subTest(base_url=base_url, has_key=bool(key_id)):
                    result = self._run_gradle_config(project, java_home, base_url, key_id)
                    combined = result.stdout + result.stderr
                    self.assertNotEqual(0, result.returncode, "Invalid reputation configuration loaded")
                    self.assertTrue(
                        "Plugin reputation base URL and authority key ID" in combined,
                        "Expected reputation configuration failure",
                    )

    def test_transport_has_only_approved_production_consumers(self):
        class_names = (
            "PluginReputationQuery",
            "PluginReputationTransportResponse",
            "PluginReputationEndpoint",
            "PluginReputationHttpTransport",
        )
        java_root = ROOT / "TMessagesProj/src/main/java"
        transport_files = {f"{class_name}.java" for class_name in class_names}

        for class_name in class_names:
            references = []

            for path in java_root.rglob("*.java"):
                if path.parent == REPUTATION and path.name in transport_files:
                    continue
                if class_name in path.read_text(encoding="utf-8"):
                    references.append(str(path.relative_to(ROOT)))

            expected = []
            if class_name == "PluginReputationQuery":
                expected = [
                    "TMessagesProj/src/main/java/org/telegram/messenger/plugins/ExternalPlugins.java",
                    "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation/"
                    "PluginReputationCoordinator.java",
                ]
            elif class_name in {
                "PluginReputationEndpoint",
                "PluginReputationHttpTransport",
            }:
                expected = [
                    "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation/"
                    "PluginReputationCoordinators.java"
                ]

            self.assertEqual(expected, references, f"Unexpected production references to {class_name}")

    @staticmethod
    def _java_home():
        jbr21 = Path(
            "/Users/hexvel/Library/Java/JavaVirtualMachines/"
            "jbr-21.0.11/Contents/Home"
        )

        if jbr21.is_dir():
            return str(jbr21)

        java_home = os.environ.get("JAVA_HOME")
        return java_home if java_home and Path(java_home).is_dir() else None

    @staticmethod
    def _run_gradle_config(project, java_home, base_url, authority_key_id):
        environment = os.environ.copy()
        environment.update(
            {
                "TELEGRAM_API_ID": "1",
                "TELEGRAM_API_HASH": "0" * 32,
                "FLUXGRAM_APPEARANCE_API_BASE_URL": "",
                "FLUXGRAM_PLUGIN_REPUTATION_API_BASE_URL": base_url,
                "FLUXGRAM_PLUGIN_REPUTATION_AUTHORITY_KEY_ID": authority_key_id,
            }
        )

        if java_home:
            environment["JAVA_HOME"] = java_home
        else:
            environment.pop("JAVA_HOME", None)

        return subprocess.run(
            [
                str(ROOT / "gradlew"),
                "-p",
                str(project),
                "verifyConfig",
                "--console=plain",
                "--quiet",
            ],
            env=environment,
            capture_output=True,
            text=True,
            timeout=60,
        )


if __name__ == "__main__":
    unittest.main()
