import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INSTALL_SHEET = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PluginInstallSheet.java"
REPORT_VIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PluginRiskReportView.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class PluginR4SourceContractTest(unittest.TestCase):
    def test_full_report_stays_inside_install_bottom_sheet(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("final class InstallBottomSheet extends BottomSheet", sheet)
        self.assertIn("sheet.setPages(content, reportView);", sheet)
        self.assertIn("if (reportVisible)", sheet)
        self.assertIn("setAllowNestedScroll(false);", sheet)
        self.assertIn("setAllowNestedScroll(true);", sheet)
        self.assertIn("return !reportVisible;", sheet)
        self.assertIn("canDismissWithTouchOutside()", sheet)
        self.assertNotIn("presentFragment", sheet)

    def test_report_contains_diff_and_only_exposes_sanitized_host_detail(self):
        report = REPORT_VIEW.read_text()

        self.assertIn("diff.addedEvidence", report)
        self.assertIn("diff.removedEvidence", report)
        self.assertIn("review.result.riskReport.evidence", report)
        self.assertIn("review.result.riskReport.limitations", report)
        self.assertIn("review.result.manifest.declaredCapabilities", report)
        self.assertIn('evidence.id.equals("network.endpoint_literal")', report)
        self.assertIn("Uri.parse(value).getHost()", report)
        self.assertNotIn("row(evidence.detail", report)
        self.assertNotIn("return evidence.detail", report)

    def test_large_report_uses_recycled_rows(self):
        report = REPORT_VIEW.read_text()

        self.assertIn("new RecyclerListView(context, resourcesProvider)", report)
        self.assertIn("extends RecyclerListView.SelectionAdapter", report)
        self.assertIn("Math.max(0, limit)", report)
        self.assertNotIn("new ScrollView", report)

    def test_report_strings_have_english_and_russian_values(self):
        report = REPORT_VIEW.read_text()
        english = STRINGS.read_text()
        russian = STRINGS_RU.read_text()
        names = set(re.findall(r"R\.string\.(FluxGramPluginRisk\w+)", report))

        self.assertTrue(names)

        for name in names:
            marker = f'<string name="{name}">'
            self.assertIn(marker, english, name)
            self.assertIn(marker, russian, name)


if __name__ == "__main__":
    unittest.main()
