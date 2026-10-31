import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins"
PYTHON = ROOT / "TMessagesProj/src/main/python/fluxgram_runtime/_risk_analysis.py"


class PluginR3SourceContractTest(unittest.TestCase):
    def test_production_review_store_and_debug_use_chaquopy_scanner(self):
        external = (JAVA / "ExternalPlugins.java").read_text()
        store = (JAVA / "packages/PluginPackageStore.java").read_text()
        validator = (JAVA / "packages/PluginPackageValidator.java").read_text()

        self.assertIn("new ChaquopyPluginPythonRiskScanner()", external)
        self.assertIn("app.getCacheDir(),\n                                        RISK_SCANNER", external)
        self.assertGreaterEqual(external.count("RISK_SCANNER"), 4)
        self.assertIn("PluginPackageValidator.inspect(frozen, cache, pythonScanner)", store)
        self.assertIn("PluginPackageValidator.inspect(frozen, cache, pythonScanner)", store)
        self.assertIn("Python risk scanner is required", validator)

    def test_adapter_prepares_host_module_without_candidate_import_path(self):
        adapter = (JAVA / "ChaquopyPluginPythonRiskScanner.java").read_text()
        helper = PYTHON.read_text()

        self.assertIn("PythonRuntime.prepare()", adapter)
        self.assertIn('getModule("fluxgram_runtime._risk_analysis")', adapter)
        self.assertNotIn("sys.path", adapter)
        self.assertNotIn("import_module", adapter)
        self.assertNotIn("__import__(", helper)
        self.assertNotIn("exec(", helper)
        self.assertNotIn("eval(", helper)

    def test_pending_scan_precedes_backup_and_publish(self):
        store = (JAVA / "packages/PluginPackageStore.java").read_text()
        freeze = store.index("copy(in, out, PluginPackageValidator.MAX_ARCHIVE_BYTES)")
        scan = store.index("PluginPackageValidator.inspect(frozen, cache, pythonScanner)")
        verify = store.index("verifyExtractedArchive(frozen, replacement)", scan)
        backup = store.index("current.renameTo(backup)", scan)
        publish = store.index("replacement.renameTo(current)", backup)

        self.assertLess(freeze, scan)
        self.assertLess(scan, verify)
        self.assertLess(verify, backup)
        self.assertLess(scan, backup)
        self.assertLess(backup, publish)


if __name__ == "__main__":
    unittest.main()
