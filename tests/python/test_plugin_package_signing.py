import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNING = (
    ROOT
    / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
)


class PluginPackageSigningTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-signing-") as output:
            sources = [
                SIGNING / "PluginSignedPayload.java",
                SIGNING / "PluginSignatureEnvelope.java",
                SIGNING / "PluginSignatureVerification.java",
                SIGNING / "PluginSignatureVerifier.java",
                SIGNING / "PluginSignerIdentity.java",
                SIGNING / "PluginKeyRotationProof.java",
                SIGNING / "PluginSignerTransition.java",
                SIGNING / "PluginSignerState.java",
                SIGNING / "PluginSignerJournal.java",
                ROOT / "tests/java/PluginPackageSigningCheck.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["java", "-cp", output, "PluginPackageSigningCheck"],
                check=True,
                capture_output=True,
                text=True,
            )


if __name__ == "__main__":
    unittest.main()
