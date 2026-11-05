package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.ExternalPlugins;
import org.telegram.messenger.plugins.Plugin;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public final class FluxGramPluginRecoverySheet extends BottomSheet {
    private static boolean showing;

    private final BaseFragment fragment;
    private final String pluginId;
    private final String pluginLabel;
    private final LinearLayout content;
    private boolean loadingReport;
    private boolean showingReport;

    private FluxGramPluginRecoverySheet(
            BaseFragment fragment,
            String pluginId,
            String pluginLabel) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        this.pluginId = pluginId;
        this.pluginLabel = pluginLabel;

        Context context = fragment.getParentActivity();
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        showRecoveryContent();
        setCustomView(content);
        setOnDismissListener(dialog -> showing = false);
    }

    private void showRecoveryContent() {
        Context context = fragment.getParentActivity();
        showingReport = false;
        content.removeAllViews();
        content.setPadding(dp(24), dp(20), dp(24), dp(12));

        TextView title = new TextView(context);
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.FluxGramPluginRecoveryTitle));
        content.addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView message = new TextView(context);
        message.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        message.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        message.setLineSpacing(dp(2), 1f);
        message.setText(LocaleController.formatString(
                "FluxGramPluginRecoveryMessage",
                R.string.FluxGramPluginRecoveryMessage,
                pluginLabel));
        content.addView(message, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 20));

        if (pluginId != null) {
            ButtonWithCounterView blockPluginButton = new ButtonWithCounterView(
                    context, resourcesProvider).setRound();
            blockPluginButton.setText(LocaleController.getString(
                    R.string.FluxGramPluginRecoveryBlockPlugin), false);
            blockPluginButton.setOnClickListener(view -> blockPlugin());
            content.addView(blockPluginButton, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 0));

            ButtonWithCounterView reportButton = new ButtonWithCounterView(
                    context, resourcesProvider).setRound().setNeutral();
            reportButton.setText(LocaleController.getString(
                    R.string.FluxGramPluginRecoveryRiskReport), false);
            reportButton.setOnClickListener(view -> openRiskReport(reportButton));
            content.addView(reportButton, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));
        }

        ButtonWithCounterView review = new ButtonWithCounterView(
                context, resourcesProvider).setRound().setNeutral();
        review.setText(LocaleController.getString(R.string.FluxGramPluginRecoveryReview), false);
        review.setOnClickListener(view -> openPlugins());
        content.addView(review, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, pluginId == null ? 0 : 6, 0, 0));

        ButtonWithCounterView keepDisabled = new ButtonWithCounterView(
                context, resourcesProvider).setRound().setNeutral();
        keepDisabled.setText(
                LocaleController.getString(R.string.FluxGramPluginRecoveryKeepDisabled), false);
        keepDisabled.setOnClickListener(view -> dismiss());
        content.addView(keepDisabled, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));
    }

    public static void maybeShow() {
        PluginManager manager = PluginManager.getInstance();

        if (showing || !manager.hasRecoveryNotice()) {
            return;
        }

        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        Activity activity = fragment == null ? null : fragment.getParentActivity();

        if (activity == null || activity.isFinishing()) {
            return;
        }

        String pluginId = manager.getRecoveryPluginId();
        Plugin plugin = pluginId == null ? null : manager.getPlugin(pluginId);
        String pluginLabel = plugin == null ? pluginId : plugin.getName() + " (" + pluginId + ")";

        if (pluginLabel == null) {
            pluginLabel = LocaleController.getString(R.string.FluxGramPluginRecoveryUnknown);
        }

        FluxGramPluginRecoverySheet sheet = new FluxGramPluginRecoverySheet(
                fragment, pluginId, pluginLabel);
        showing = true;

        try {
            sheet.show();

            if (!manager.claimRecoveryNotice()) {
                sheet.dismiss();
            }
        } catch (RuntimeException error) {
            showing = false;
            FileLog.e(error);
        }
    }

    private void openPlugins() {
        PluginManager manager = PluginManager.getInstance();
        manager.setSystemEnabled(true);

        if (manager.isRecoveryLatched()) {
            return;
        }

        ExternalPlugins.changed();
        dismiss();
        fragment.presentFragment(FluxGramSettingsActivity.plugins());
    }

    private void blockPlugin() {
        if (!ExternalPlugins.blockStartup(pluginId)) {
            return;
        }

        openPlugins();
    }

    private void openRiskReport(ButtonWithCounterView button) {
        if (loadingReport) {
            return;
        }

        loadingReport = true;
        button.setLoading(true);
        ExternalPlugins.loadInstalledRiskReport(pluginId, (report, error) -> {
            loadingReport = false;

            if (!isShowing() || isDismissed()) {
                return;
            }

            button.setLoading(false);
            PluginManager manager = PluginManager.getInstance();

            if (!manager.isRecoveryLatched()
                    || !pluginId.equals(manager.getRecoveryPluginId())) {
                return;
            }
            if (error != null || report == null) {
                showReportUnavailable();
                return;
            }

            showRiskReport(report);
        });
    }

    private void showRiskReport(ExternalPlugins.InstalledRiskReport installed) {
        Context context = fragment.getParentActivity();
        showingReport = true;
        content.removeAllViews();
        content.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView title = new TextView(context);
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(LocaleController.getString(R.string.FluxGramPluginRiskReportTitle));
        content.addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));

        TextView plugin = new TextView(context);
        plugin.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        plugin.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        plugin.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        plugin.setText(installed.manifest.name + "  ·  " + installed.manifest.version);
        content.addView(plugin, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 8, 8, 0));

        TextView notice = new TextView(context);
        notice.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        notice.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        notice.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        notice.setLineSpacing(dp(2), 1f);
        notice.setText(LocaleController.getString(
                R.string.FluxGramPluginRecoveryRiskReportInfo));
        content.addView(notice, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 6, 8, 0));

        content.addView(new PluginRiskReportView(
                context, installed, resourcesProvider), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ButtonWithCounterView back = new ButtonWithCounterView(
                context, resourcesProvider).setRound().setNeutral();
        back.setText(LocaleController.getString(R.string.Back), false);
        back.setOnClickListener(view -> showRecoveryContent());
        content.addView(back, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 8, 0, 8, 0));
    }

    private void showReportUnavailable() {
        new AlertDialog.Builder(fragment.getParentActivity(), resourcesProvider)
                .setTitle(LocaleController.getString(R.string.FluxGramPluginRiskReportTitle))
                .setMessage(LocaleController.getString(
                        R.string.FluxGramPluginRecoveryRiskReportUnavailable))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (showingReport) {
            showRecoveryContent();
        } else {
            super.onBackPressed();
        }
    }
}
