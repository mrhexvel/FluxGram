import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXTERNAL = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/messenger/plugins/ExternalPlugins.java"
)
SETTINGS = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/ui/FluxGramSettingsActivity.java"
)
CELL = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/ui/Components/FluxGramPluginCell.java"
)


class PluginReputationStartupContractTest(unittest.TestCase):
    def test_terminal_decision_precedes_migration_and_registration(self):
        source = EXTERNAL.read_text(encoding="utf-8")
        task = source[source.index("installedReputationTask(") :]

        terminal = task.index("PluginReputationInstallPolicy.evaluate(result)")
        migration = task.index("PythonPluginAdapter.migrateStorage(installed)")
        registration = task.index("manager.register(adapter, false)")

        self.assertLess(terminal, migration)
        self.assertLess(terminal, registration)
        self.assertIn("requireCurrentIdentity(", task[:migration])
        self.assertIn("clearPersistedEnabledStateNow", task[:migration])

    def test_startup_lookup_has_bounded_installer_worker_deadline(self):
        external = EXTERNAL.read_text(encoding="utf-8")
        pump = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation/"
            "PluginReputationStartupPump.java"
        ).read_text(encoding="utf-8")

        self.assertIn("ScheduledExecutorService worker", external)
        self.assertIn("STARTUP_REPUTATION_TIMEOUT_MS = 12_000", external)
        self.assertIn("worker.schedule(", external)
        self.assertIn("PluginReputationCoordinator.Request start(", pump)
        self.assertIn("request.cancel();", pump)
        self.assertIn("finish(task, null);", pump)
        self.assertIn("requestSequence != sequence", pump)

    def test_pending_block_rolls_back_before_baseline_gate(self):
        source = EXTERNAL.read_text(encoding="utf-8")
        pending = source[
            source.index("private static void recoverPendingReputation") :
            source.index("private static void loadInstalledWithReputation")
        ]

        block = pending.index("PluginReputationInstallPolicy.Decision.BLOCK")
        rollback = pending.index("rollbackPending(manager, operation)", block)
        migration = pending.index("PythonPluginAdapter.migrateStorage(update)")

        self.assertLess(block, rollback)
        self.assertLess(rollback, migration)
        self.assertIn("operation.enabledBeforeUpdate", pending)
        self.assertIn("store.finishPendingRollback(operation.id)", pending)

    def test_metadata_wrappers_remain_visible_without_settings_or_toggle(self):
        external = EXTERNAL.read_text(encoding="utf-8")
        settings = SETTINGS.read_text(encoding="utf-8")
        cell = CELL.read_text(encoding="utf-8")

        self.assertIn("State.CHECKING", external)
        self.assertIn("State.BLOCKED", external)
        self.assertIn("plugins.addAll(ExternalPlugins.getIncompatiblePlugins())", settings)
        self.assertIn("if (!metadataOnly)", settings)
        self.assertIn("metadataOnly ? null", settings)
        self.assertIn("R.drawable.msg_share", settings)
        self.assertIn("R.drawable.msg_delete", settings)
        self.assertIn("Predicate<Boolean> toggle", cell)
        self.assertIn("if (toggle.test(next))", cell)

    def test_identity_uses_signed_key_and_legacy_risk_fallback(self):
        source = EXTERNAL.read_text(encoding="utf-8")
        identity = source[
            source.index("private static PluginReputationQuery reputationQuery") :
            source.index("private static PluginPackageStore.Installed requireCurrentIdentity")
        ]

        self.assertIn("installed.signerState.archiveSha256", identity)
        self.assertIn("PluginSignerIdentity.Kind.SIGNED", identity)
        self.assertIn("installed.signerState.identity.keyId", identity)
        self.assertIn("installed.riskReport.archiveSha256", identity)
        self.assertIn("return null", identity)


if __name__ == "__main__":
    unittest.main()
