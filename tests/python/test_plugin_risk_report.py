import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RISK = (
    ROOT
    / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/risk"
)


class PluginRiskReportTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-risk-") as output:
            sources = [
                RISK / "PluginRiskEvidence.java",
                RISK / "PluginPythonRiskScanner.java",
                RISK / "PluginRiskCatalog.java",
                RISK / "PluginRiskReport.java",
                RISK / "PluginRiskDiff.java",
                RISK / "PluginRiskPolicy.java",
                ROOT / "tests/java/PluginRiskReportCheck.java",
            ]
            subprocess.run(
                ["javac", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["java", "-cp", output, "PluginRiskReportCheck"],
                check=True,
                capture_output=True,
                text=True,
            )


if __name__ == "__main__":
    unittest.main()
