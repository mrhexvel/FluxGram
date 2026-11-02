import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JOURNAL = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/journal"


class PluginRuntimeJournalTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-runtime-journal-") as output:
            sources = [
                JOURNAL / "PluginRuntimeJournalCatalog.java",
                JOURNAL / "PluginRuntimeJournalEvent.java",
                JOURNAL / "PluginRuntimeJournalSnapshot.java",
                ROOT / "tests/java/PluginRuntimeJournalCheck.java",
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["java", "-cp", output, "PluginRuntimeJournalCheck"],
                check=True,
                capture_output=True,
                text=True,
            )

    def test_model_has_no_freeform_sensitive_fields(self):
        source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(JOURNAL.glob("*.java"))
        )
        forbidden = (
            "messageText",
            "dialogId",
            "messageId",
            "topicId",
            "senderId",
            "storageKey",
            "storageValue",
            "storagePath",
            "throwableMessage",
            "stackTrace",
            "Map<String",
            "Throwable",
        )

        for name in forbidden:
            self.assertNotIn(name, source)

        self.assertNotIn("String detail", source)
        self.assertNotIn("String metadata", source)
        self.assertNotIn("long subjectToken", source)
        self.assertIn("MANAGED_HOOK", source)
        self.assertIn("MANAGED_STORAGE", source)
        self.assertIn("TYPED_SUBSCRIPTION", source)


if __name__ == "__main__":
    unittest.main()
