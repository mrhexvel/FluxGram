import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNING = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
REPUTATION = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation"


class PluginReputationTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-reputation-") as output:
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
                ROOT / "tests/java/PluginReputationCheck.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["java", "-cp", output, "PluginReputationCheck"],
                check=True,
                capture_output=True,
                text=True,
            )


if __name__ == "__main__":
    unittest.main()
