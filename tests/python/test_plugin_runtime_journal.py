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
                JOURNAL / "PluginRuntimeJournalCollector.java",
                JOURNAL / "PluginRuntimeJournalCoordinator.java",
                JOURNAL / "PluginRuntimeJournalEvent.java",
                JOURNAL / "PluginRuntimeJournalInput.java",
                JOURNAL / "PluginRuntimeJournalSnapshot.java",
                JOURNAL / "PluginRuntimeJournalStore.java",
                ROOT / "tests/java/PluginRuntimeJournalCheck.java",
                ROOT / "tests/java/PluginRuntimeJournalCoordinatorCheck.java",
                ROOT / "tests/java/PluginRuntimeJournalStoreCheck.java",
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
            subprocess.run(
                ["java", "-cp", output, "PluginRuntimeJournalStoreCheck"],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["java", "-cp", output, "PluginRuntimeJournalCoordinatorCheck"],
                check=True,
                capture_output=True,
                text=True,
            )

    def test_model_has_no_freeform_sensitive_fields(self):
        source = "\n".join(
            (JOURNAL / name).read_text(encoding="utf-8")
            for name in (
                "PluginRuntimeJournalCatalog.java",
                "PluginRuntimeJournalEvent.java",
                "PluginRuntimeJournalInput.java",
                "PluginRuntimeJournalSnapshot.java",
            )
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

    def test_collector_is_bounded_and_nonblocking_for_producers(self):
        source = (JOURNAL / "PluginRuntimeJournalCollector.java").read_text(
            encoding="utf-8"
        )

        self.assertIn("MAX_PENDING_EVENTS = 256", source)
        self.assertIn("MAX_BATCH_EVENTS = 64", source)
        self.assertIn("queueLock.tryLock()", source)
        self.assertNotIn("queueLock.lockInterruptibly()", source)
        self.assertNotIn("BlockingQueue", source)
        self.assertNotIn(".put(", source)
        self.assertNotIn(".take(", source)

    def test_android_files_use_descriptor_boundary_and_lifetime_lock(self):
        source = (JOURNAL / "PluginRuntimeJournalAndroidFiles.java").read_text(
            encoding="utf-8"
        )

        self.assertIn("OsConstants.O_NOFOLLOW", source)
        self.assertIn("Os.fstat", source)
        self.assertIn("Os.fsync", source)
        self.assertIn("Os.rename", source)
        self.assertIn("FileChannel", source)
        self.assertIn("channel.lock()", source)
        self.assertIn("processLock.lock()", source)
        self.assertIn("processLock.unlock()", source)
        self.assertNotIn("PluginManager", source)
        self.assertNotIn("PythonPluginAdapter", source)

    def test_production_composition_is_bounded_and_has_no_event_source(self):
        composition = (JOURNAL / "PluginRuntimeJournals.java").read_text(
            encoding="utf-8"
        )
        application = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java"
        ).read_text(encoding="utf-8")

        self.assertIn("new ArrayBlockingQueue<>(4)", composition)
        self.assertIn("getNoBackupFilesDir()", composition)
        self.assertIn('"runtime_journal"', composition)
        self.assertNotIn("newSingleThreadExecutor", composition)
        self.assertNotIn("PluginManager", composition)
        self.assertNotIn("ExternalPlugins", composition)
        self.assertNotIn("record(", composition)
        self.assertLess(
            application.index("PluginRuntimeJournals.initialize(applicationContext)"),
            application.index("ExternalPlugins.initialize(applicationContext)"),
        )


if __name__ == "__main__":
    unittest.main()
