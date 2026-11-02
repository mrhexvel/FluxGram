import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNING = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
REPUTATION = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation"
APPLICATION_LOADER = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java"
)
EXTERNAL_PLUGINS = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/messenger/plugins/ExternalPlugins.java"
)


class PluginReputationCoordinatorTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-reputation-coordinator-") as output:
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
                REPUTATION / "PluginReputationState.java",
                REPUTATION / "PluginReputationPolicy.java",
                REPUTATION / "PluginReputationCache.java",
                REPUTATION / "PluginReputationQuery.java",
                REPUTATION / "PluginReputationCoordinator.java",
                REPUTATION / "PluginReputationInstallPolicy.java",
                ROOT / "tests/java/PluginReputationCoordinatorCheck.java",
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
                    "org.telegram.messenger.plugins.reputation.PluginReputationCoordinatorCheck",
                ],
                check=True,
                capture_output=True,
                text=True,
            )

    def test_production_composition_is_lazy_and_has_one_install_consumer(self):
        coordinator = (REPUTATION / "PluginReputationCoordinators.java").read_text(
            encoding="utf-8"
        )
        application = APPLICATION_LOADER.read_text(encoding="utf-8")
        external_plugins = EXTERNAL_PLUGINS.read_text(encoding="utf-8")
        java_root = ROOT / "TMessagesProj/src/main/java"

        self.assertIn("context.getNoBackupFilesDir()", coordinator)
        self.assertIn('new File(pluginRoot, "reputation")', coordinator)
        self.assertIn("PluginReputationCoordinators.initialize(applicationContext)", application)
        self.assertLess(
            application.index("PluginReputationCoordinators.initialize(applicationContext)"),
            application.index("ExternalPlugins.initialize(applicationContext)"),
        )
        self.assertNotIn(".lookup(", coordinator)
        self.assertNotIn("PluginReputationCoordinators.get()", application)
        self.assertIn(
            "PluginReputationCoordinators.get().lookup(query, worker, result ->",
            external_plugins,
        )

        consumers = []
        for path in java_root.rglob("*.java"):
            if path.name in {
                "PluginReputationCoordinator.java",
                "PluginReputationCoordinators.java",
            }:
                continue
            if "PluginReputationCoordinators.get()" in path.read_text(encoding="utf-8"):
                consumers.append(str(path.relative_to(ROOT)))

        self.assertEqual(
            [
                "TMessagesProj/src/main/java/org/telegram/messenger/plugins/"
                "ExternalPlugins.java"
            ],
            consumers,
        )

    def test_install_consumer_waits_for_terminal_authenticated_block(self):
        source = EXTERNAL_PLUGINS.read_text(encoding="utf-8")

        lookup = source.index("PluginReputationCoordinators.get().lookup")
        policy = source.index("PluginReputationInstallPolicy.evaluate(result)", lookup)
        waiting = source.index("PluginReputationInstallPolicy.Decision.WAIT", policy)
        blocked = source.index("PluginReputationInstallPolicy.Decision.BLOCK", waiting)
        continuation = source.index("installApproved(", blocked)
        store_update = source.index("store.scheduleUpdate(", continuation)
        store_install = source.index("store.install(", continuation)
        migration = source.index("PythonPluginAdapter.migrateStorage(installed)", continuation)

        self.assertLess(lookup, policy)
        self.assertLess(policy, waiting)
        self.assertLess(waiting, blocked)
        self.assertLess(blocked, continuation)
        self.assertLess(continuation, store_update)
        self.assertLess(continuation, store_install)
        self.assertLess(continuation, migration)
        self.assertIn("new PluginReputationQuery(\n                    review.result.sha256,", source)
        self.assertIn("review.result.signatureVerification.isVerified()", source)
        self.assertIn("? review.result.signatureVerification.keyId", source)


if __name__ == "__main__":
    unittest.main()
