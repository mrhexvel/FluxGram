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
                JOURNAL / "PluginRuntimeJournalExport.java",
                JOURNAL / "PluginRuntimeJournalInput.java",
                JOURNAL / "PluginRuntimeJournalSnapshot.java",
                JOURNAL / "PluginRuntimeJournalStore.java",
                ROOT / "tests/java/PluginRuntimeJournalCheck.java",
                ROOT / "tests/java/PluginRuntimeJournalCoordinatorCheck.java",
                ROOT / "tests/java/PluginRuntimeJournalExportCheck.java",
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
                ["java", "-cp", output, "PluginRuntimeJournalExportCheck"],
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
        self.assertLess(
            application.index("PluginRuntimeJournals.initialize(applicationContext)"),
            application.index("ExternalPlugins.initialize(applicationContext)"),
        )

    def test_export_is_bounded_redacted_and_read_only(self):
        export = (JOURNAL / "PluginRuntimeJournalExport.java").read_text(
            encoding="utf-8"
        )
        coordinator = (JOURNAL / "PluginRuntimeJournalCoordinator.java").read_text(
            encoding="utf-8"
        )

        self.assertIn("MAX_ENCODED_BYTES = 512 * 1024", export)
        self.assertIn('field(output, "coverage", "host_managed_fluxgram_api_only")', export)
        self.assertIn('string(output, "direct_xposed_or_aliuhook")', export)
        self.assertIn('string(output, "raw_network_files_or_database")', export)
        self.assertNotIn('field(output, "process_instance_id"', export)
        self.assertNotIn('field(output, "runtime_instance_id"', export)
        self.assertNotIn('field(output, "subject_token"', export)
        self.assertIn("MAX_PENDING_EXPORTS = 8", coordinator)
        self.assertIn("PluginRuntimeJournalExport.create(store.snapshot())", coordinator)
        self.assertNotIn("store.load()", coordinator[coordinator.index("public ExportRequestResult export"):])

    def test_running_producer_is_external_and_after_lifecycle_transition(self):
        adapter = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/PythonPluginAdapter.java"
        ).read_text(encoding="utf-8")
        java_session = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/JavaPluginSession.java"
        ).read_text(encoding="utf-8")

        running = adapter.index(
            "lifecycle.setState(request, PythonPluginLifecycle.State.RUNNING)"
        )
        journal = adapter.index("journalRunning(request)")
        changed = adapter.index("ExternalPlugins.changed()", journal)
        self.assertLess(running, journal)
        self.assertLess(journal, changed)
        self.assertIn("if (!isExternal()", adapter)
        self.assertIn("archiveSha256 == null", adapter)
        self.assertIn("installed.signerState.archiveSha256", adapter)
        self.assertIn("installed.riskReport.archiveSha256", adapter)
        self.assertIn("RUNTIME_GENERATION_RUNNING", adapter)
        self.assertNotIn("PluginRuntimeJournals", java_session)

    def test_unload_producer_records_confirmed_cleanup_outcome(self):
        adapter = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/PythonPluginAdapter.java"
        ).read_text(encoding="utf-8")

        regular_unload = adapter[adapter.index("private boolean unload(") :]
        generation = regular_unload.index("pluginSession.getGeneration()")
        unload = regular_unload.index("unloadAndJournal(generation);")
        failure_handler = regular_unload.index('handleFailure(request, "Unload"')
        self.assertLess(generation, unload)
        self.assertLess(unload, failure_handler)
        self.assertNotIn("journalUnload(request", regular_unload)

        removal = adapter[
            adapter.index("public void stopForRemoval()") : adapter.index(
                "private boolean unload("
            )
        ]
        self.assertIn("? 0 : pluginSession.getGeneration()", removal)
        self.assertIn("unloadAndJournal(generation);", removal)
        self.assertNotIn("journalUnload(generation", removal)

        journaled_unload = adapter[
            adapter.index("private void unloadAndJournal(") : adapter.index(
                "private void unloadStrict("
            )
        ]
        self.assertLess(
            journaled_unload.index("journalUnload(generation, false);"),
            journaled_unload.index("throw error;"),
        )
        self.assertLess(
            journaled_unload.index("unloadStrict();"),
            journaled_unload.index("journalUnload(generation, true);"),
        )
        self.assertIn("CLEANUP_UNLOAD_SUCCEEDED", adapter)
        self.assertIn("CLEANUP_UNLOAD_FAILED", adapter)
        self.assertIn("generation <= 0", adapter)
        self.assertNotIn("PluginRuntimeJournals", (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/PluginResourceRegistry.java"
        ).read_text(encoding="utf-8"))

    def test_journal_screen_is_lifecycle_safe_and_available_when_disabled(self):
        activity = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/ui/PluginRuntimeJournalActivity.java"
        ).read_text(encoding="utf-8")
        cell = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/ui/Components/PluginRuntimeJournalSummaryCell.java"
        ).read_text(encoding="utf-8")
        settings = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/ui/FluxGramSettingsActivity.java"
        ).read_text(encoding="utf-8")

        self.assertIn("PluginRuntimeJournals.export", activity)
        self.assertIn("request != requestGeneration", activity)
        self.assertIn("requestGeneration++;\n        loading = false;", activity)
        self.assertIn("AndroidUtilities.runOnUIThread", activity)
        self.assertNotIn("JSONObject", activity)
        self.assertNotIn("FluxGramPluginJournalScopeInfo", activity)
        self.assertIn("PluginRuntimeJournalSummaryCell.Factory.of", activity)
        self.assertNotIn("aliasNumber", activity)
        self.assertNotIn("journal.droppedEvents", activity)
        self.assertIn("FluxGramPluginJournalSummary", activity)
        self.assertIn("FluxGramPluginJournalSummaryWithErrors", activity)
        self.assertIn("extends UItem.UItemFactory", cell)
        plugin_section = settings.index("items.add(UItem.asShadow(systemDescription))")
        self.assertLess(
            settings.index("PLUGIN_JOURNAL,", plugin_section),
            settings.index("if (!systemEnabled)", plugin_section),
        )


if __name__ == "__main__":
    unittest.main()
