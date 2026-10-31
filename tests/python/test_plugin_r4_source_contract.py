import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INSTALL_SHEET = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PluginInstallSheet.java"
REPORT_VIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/PluginRiskReportView.java"
STRINGS = ROOT / "TMessagesProj/src/main/res/values/strings.xml"
STRINGS_RU = ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml"


class PluginR4SourceContractTest(unittest.TestCase):
    def test_full_report_opens_in_owned_install_bottom_sheet(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("final class InstallBottomSheet extends BottomSheet", sheet)
        self.assertIn("sheet.setPages(content, stateView);", sheet)
        self.assertIn("private void showReport(ExternalPlugins.Review review)", sheet)
        self.assertIn("if (transferred[0])", sheet)
        self.assertIn("showReport(review);", sheet)
        self.assertIn("boolean[] transferred = {false};", sheet)
        self.assertIn("new PluginRiskReportView(", sheet)
        self.assertIn("setAllowNestedScroll(false);", sheet)
        self.assertIn("setAllowNestedScroll(true);", sheet)
        self.assertIn("state.setVisibility(View.GONE);", sheet)
        self.assertIn("previous.setVisibility(View.GONE);", sheet)
        self.assertNotIn("previous.animate()\n                    .alpha", sheet)
        self.assertIn("return !busy && (!stateVisible || terminal);", sheet)
        self.assertIn("canDismissWithTouchOutside()", sheet)
        self.assertNotIn("presentFragment", sheet)

    def test_confirmation_and_terminal_states_stay_inside_sheet(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("final class InstallStateView extends LinearLayout", sheet)
        self.assertIn("stateView.showConfirmation", sheet)
        self.assertIn("stateView.showInstalling", sheet)
        self.assertIn("stateView.showInstalled", sheet)
        self.assertIn("stateView.showUpdated", sheet)
        self.assertIn("stateView.showError", sheet)
        self.assertIn("sheet.setBusy(true);", sheet)
        self.assertIn("if (sheet.canConfirm())", sheet)
        self.assertIn("if (stateVisible && terminal)", sheet)
        self.assertNotIn("AlertDialog confirmation", sheet)

    def test_success_state_uses_theme_aware_animation(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("new RLottieImageView(context)", sheet)
        self.assertIn("setAnimation(R.raw.plugin_install_success, 100, 100)", sheet)
        self.assertIn('setLayerColor("Success.Primary", primary)', sheet)
        self.assertIn("SharedConfig.animationsEnabled()", sheet)
        self.assertNotIn("addHeroIcon(R.drawable.msg_check_s", sheet)

    def test_risk_confirmation_uses_theme_aware_animation(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("setAnimation(R.raw.risk_plugin_shield, 100, 100)", sheet)
        self.assertIn('"Risk.Primary"', sheet)
        self.assertIn('"Risk.Accent"', sheet)
        self.assertIn('"Risk.Highlight"', sheet)
        self.assertNotIn(
            "addHeroIcon(R.drawable.outline_shield_plain_24", sheet
        )

    def test_sheet_clicks_use_system_respecting_haptics(self):
        sheet = INSTALL_SHEET.read_text()
        report = REPORT_VIEW.read_text()

        self.assertIn("performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)", sheet)
        self.assertIn("performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)", report)
        self.assertNotIn("FLAG_IGNORE_GLOBAL_SETTING", sheet)
        self.assertNotIn("FLAG_IGNORE_VIEW_SETTING", sheet)
        self.assertNotIn("FLAG_IGNORE_GLOBAL_SETTING", report)
        self.assertNotIn("FLAG_IGNORE_VIEW_SETTING", report)

    def test_signer_and_risk_share_one_confirmation_page(self):
        sheet = INSTALL_SHEET.read_text()

        self.assertIn("confirmationMessage(review)", sheet)
        self.assertIn("PluginSignerTransition.Decision.CONFIRM", sheet)
        self.assertIn("review.signerReview.transition.reason", sheet)
        self.assertIn("confirmed[0] = true;", sheet)
        self.assertEqual(sheet.count("stateView.showConfirmation("), 2)

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
