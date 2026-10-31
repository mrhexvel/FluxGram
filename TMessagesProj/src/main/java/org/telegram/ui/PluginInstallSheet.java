package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import org.telegram.ui.Components.FluxGramPluginIconView;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.ExternalPlugins;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.messenger.plugins.packages.PluginManifest;
import org.telegram.messenger.plugins.packages.PluginPackageStore;
import org.telegram.messenger.plugins.risk.PluginRiskPolicy;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class PluginInstallSheet {
    private static final Set<BaseFragment> reviewing = Collections.newSetFromMap(new WeakHashMap<>());
    private final BaseFragment fragment;

    private PluginInstallSheet(BaseFragment fragment) {
        this.fragment = fragment;
    }

    public static boolean isPluginFile(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".fgplugin");
    }

    public static boolean openMessage(BaseFragment fragment, MessageObject message) {
        if (!isPluginFile(message.getFileName()) && !isPluginFile(message.getDocumentName())) {
            return false;
        }

        File file = null;

        if (message.messageOwner.attachPath != null && !message.messageOwner.attachPath.isEmpty()) {
            file = new File(message.messageOwner.attachPath);
        }

        if (file == null || !file.isFile()) {
            file = FileLoader.getInstance(message.currentAccount).getPathToMessage(message.messageOwner);
        }

        openFile(fragment, file);

        return true;
    }

    public static void openFile(BaseFragment fragment, File file) {
        if (fragment == null) {
            return;
        }

        if (file == null || !file.isFile()) {
            new PluginInstallSheet(fragment).showError(new java.io.IOException(LocaleController.getString(R.string.FluxGramPluginDownloadFirst)));

            return;
        }

        open(fragment, Uri.fromFile(file));
    }

    public static void open(BaseFragment fragment, Uri uri) {
        PluginInstallSheet controller = new PluginInstallSheet(fragment);

        if (!controller.isUsable() || !reviewing.add(fragment)) {
            return;
        }

        ExternalPlugins.review(fragment.getParentActivity(), uri, (review, error) -> {
            reviewing.remove(fragment);

            if (!controller.isUsable() || !fragment.isLastFragment() || fragment.isPaused()) {
                if (review != null) {
                    review.close();
                }

                return;
            }

            if (error != null) {
                controller.showError(error);
            } else {
                controller.show(review);
            }
        });
    }

    private boolean isUsable() {
        return !fragment.isFinished && fragment.getParentActivity() != null && !fragment.getParentActivity().isFinishing();
    }

    private boolean isPresentationUsable() {
        return isUsable() && fragment.isLastFragment() && !fragment.isPaused();
    }

    private void showError(Exception error) {
        if (!isUsable()) {
            return;
        }

        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider()).setTitle(LocaleController.getString(R.string.FluxGramPluginError)).setMessage(error.getMessage()).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private TextView sheetText(Context context, CharSequence text, int size, boolean secondary) {
        TextView view = new LinkSpanDrawable.LinksTextView(context, fragment.getResourceProvider());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(fragment.getThemedColor(secondary ? Theme.key_dialogTextGray : Theme.key_dialogTextBlack));
        view.setPadding(0, dp(8), 0, dp(8));

        return view;
    }

    private void show(ExternalPlugins.Review review) {
        Context context = fragment.getParentActivity();
        PluginManifest manifest = review.result.manifest;
        boolean update = review.installedVersion != null;
        PluginPackageStore.Pending pending = ExternalPlugins.getPending(manifest.id);
        boolean sameVersion = update && manifest.version.equals(review.installedVersion);
        boolean newer = !update || PluginPackageStore.compareVersions(
                manifest.version, review.installedVersion) > 0;
        boolean blocked = review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK;
        boolean canInstall = pending == null && newer && !blocked;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(12));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = (int) (AndroidUtilities.displaySize.y * 0.85f) - dp(132);
                int limit = Math.min(MeasureSpec.getSize(heightMeasureSpec), available);

                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    limit = available;
                }

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.max(dp(100), limit), MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(4), 0, dp(4), dp(12));

        FluxGramPluginIconView icon = new FluxGramPluginIconView(
                context, fragment.getResourceProvider(), manifest.id, manifest.name);
        icon.setRoundRadius(dp(20));

        if (review.icon != null) {
            icon.setImageBitmap(review.icon);
        }

        details.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 12));

        TextView title = sheetText(context, manifest.name, 21, false);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        details.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView metadata = sheetText(context,
                LocaleController.getString(R.string.FluxGramPluginVersion) + " " + manifest.version
                        + " · " + manifest.author, 14, true);
        metadata.setGravity(Gravity.CENTER);
        metadata.setPadding(0, 0, 0, dp(20));
        details.addView(metadata, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!canInstall && !blocked) {
            details.addView(sheetText(context, LocaleController.getString(pending != null ? R.string.FluxGramPluginRestartRequired : sameVersion ? R.string.FluxGramPluginAlreadyInstalled : R.string.FluxGramPluginNewerVersionRequired), 15, true));
        }

        if (update && canInstall) {
            details.addView(sheetText(context, review.installedVersion + " → " + manifest.version, 18, false));
            details.addView(sheetText(context, LocaleController.getString(R.string.FluxGramPluginUpdateInfo), 14, true));
        }

        details.addView(sheetText(context, manifest.description, 16, false));
        details.addView(createRiskSummary(context, review, update), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 12, 0, 12));
        details.addView(sheetText(context, LocaleController.getString(R.string.FluxGramFullAccess), 17, false));
        details.addView(sheetText(context, LocaleController.getString(R.string.FluxGramPluginTrust), 15, false));

        scroll.addView(details);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView install = sheetText(context,
                LocaleController.getString(blocked
                        ? R.string.FluxGramPluginRiskBlocked
                        : update ? R.string.FluxGramUpdatePlugin : R.string.FluxGramInstallPlugin),
                16, false);
        install.setEnabled(canInstall);
        install.setAlpha(canInstall ? 1f : 0.5f);
        install.setGravity(Gravity.CENTER);
        install.setTypeface(AndroidUtilities.bold());
        install.setTextColor(fragment.getThemedColor(Theme.key_featuredStickers_buttonText));
        install.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(25), fragment.getThemedColor(Theme.key_featuredStickers_addButton),
                fragment.getThemedColor(Theme.key_listSelector)));
        content.addView(install, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 8, 0, 10));

        CheckBoxCell enable = new CheckBoxCell(
                context, CheckBoxCell.TYPE_CHECK_BOX_UNKNOWN, fragment.getResourceProvider());
        enable.setText(LocaleController.getString(update
                        ? R.string.FluxGramEnableAfterUpdate : R.string.FluxGramEnableAfterInstall),
                "", update && PluginManager.getInstance().isEnabled(manifest.id), false);
        enable.setEnabled(canInstall);
        enable.setAlpha(canInstall ? 1f : 0.5f);
        enable.setOnClickListener(view -> enable.setChecked(!enable.isChecked(), true));
        enable.setVisibility(blocked ? View.GONE : View.VISIBLE);
        content.addView(enable, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, 50, Gravity.CENTER_HORIZONTAL));

        BottomSheet sheet = new BottomSheet.Builder(context, false, fragment.getResourceProvider()).setCustomView(content).create();
        boolean[] installing = {false};
        boolean[] active = {true};
        boolean[] confirming = {false};
        Runnable installAction = () -> {
            if (!active[0] || installing[0] || !canInstall || !isPresentationUsable()) {
                return;
            }

            installing[0] = true;
            install.setEnabled(false);
            enable.setEnabled(false);
            install.setText(LocaleController.getString(R.string.FluxGramPluginInstalling));

            ExternalPlugins.install(
                    review,
                    enable.isChecked(),
                    update ? review.installedVersion : null,
                    (id, error) -> {
                        boolean canPresent = active[0] && isPresentationUsable();
                        sheet.dismiss();

                        if (!canPresent) {
                            return;
                        }

                        if (error != null) {
                            showError(error);
                        } else {
                            ExternalPlugins.changed();

                            if (update) {
                                showRestartInfo();
                            } else {
                                android.widget.Toast.makeText(
                                        context,
                                        LocaleController.getString(R.string.FluxGramPluginInstalled),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        };

        install.setOnClickListener(view -> {
            if (!active[0] || installing[0] || !canInstall) {
                return;
            }

            if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM) {
                confirming[0] = true;
                AlertDialog confirmation = new AlertDialog.Builder(
                        context, fragment.getResourceProvider())
                        .setTitle(LocaleController.getString(update
                                ? R.string.FluxGramPluginRiskUpdateConfirmTitle
                                : R.string.FluxGramPluginRiskConfirmTitle))
                        .setMessage(riskMessage(review, false))
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(
                                LocaleController.getString(R.string.FluxGramPluginRiskConfirmAction),
                                (dialog, which) -> {
                                    confirming[0] = false;
                                    installAction.run();
                                })
                        .create();
                if (fragment.showDialog(confirmation, dialog -> {
                    if (!installing[0]) {
                        active[0] = false;
                        review.close();
                    }
                }) == null) {
                    confirming[0] = false;
                }
            } else {
                installAction.run();
            }
        });

        if (fragment.showDialog(sheet, dialog -> {
            if (confirming[0]) {
                return;
            }

            active[0] = false;

            if (!installing[0]) {
                review.close();
            }
        }) == null) {
            review.close();
        }
    }

    private LinearLayout createRiskSummary(
            Context context, ExternalPlugins.Review review, boolean update) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));

        int color;
        int titleId;

        if (review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK) {
            color = fragment.getThemedColor(Theme.key_text_RedBold);
            titleId = R.string.FluxGramPluginRiskBlocked;
        } else if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM) {
            color = fragment.getThemedColor(Theme.key_avatar_nameInMessageOrange);
            titleId = R.string.FluxGramPluginRiskConfirm;
        } else {
            color = fragment.getThemedColor(Theme.key_windowBackgroundWhiteGreenText);
            titleId = update
                    ? R.string.FluxGramPluginRiskUpdateChecked
                    : R.string.FluxGramPluginRiskChecked;
        }

        card.setBackground(Theme.createRoundRectDrawable(dp(14), Theme.multAlpha(color, 0.10f)));

        TextView title = sheetText(context, LocaleController.getString(titleId), 16, false);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(color);
        title.setPadding(0, 0, 0, dp(4));
        card.addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView message = sheetText(context, riskMessage(review, update), 14, false);
        message.setPadding(0, 0, 0, 0);
        card.addView(message, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return card;
    }

    private CharSequence riskMessage(ExternalPlugins.Review review, boolean update) {
        if (review.riskPolicy.decision == PluginRiskPolicy.Decision.ALLOW) {
            return LocaleController.getString(update
                    ? R.string.FluxGramPluginRiskUpdateCheckedInfo
                    : R.string.FluxGramPluginRiskCheckedInfo);
        }

        StringBuilder message = new StringBuilder(LocaleController.getString(
                review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK
                        ? R.string.FluxGramPluginRiskBlockedInfo
                        : R.string.FluxGramPluginRiskConfirmInfo));
        List<CharSequence> reasons = riskReasons(review.riskPolicy.reasons);

        for (CharSequence reason : reasons) {
            message.append("\n\u2022 ").append(reason);
        }

        return message;
    }

    private List<CharSequence> riskReasons(List<String> reasonIds) {
        ArrayList<CharSequence> result = new ArrayList<>();
        LinkedHashSet<Integer> known = new LinkedHashSet<>();

        for (String reasonId : reasonIds) {
            int stringId = riskReasonString(reasonId);

            if (stringId != 0) {
                if (known.add(stringId)) {
                    result.add(LocaleController.getString(stringId));
                }
            } else {
                result.add(LocaleController.formatString(
                        R.string.FluxGramPluginRiskReasonUnknown, reasonId));
            }
        }

        return result;
    }

    private int riskReasonString(String id) {
        if (id.equals("update.previous_report_unavailable")) {
            return R.string.FluxGramPluginRiskReasonBaseline;
        }
        if (id.startsWith("analysis.")) {
            return R.string.FluxGramPluginRiskReasonAnalysis;
        }
        if (id.equals("block.module.digest_mismatch")
                || id.equals("block.host_fingerprint_mismatch")) {
            return R.string.FluxGramPluginRiskReasonIntegrity;
        }
        if (id.startsWith("block.")) {
            return R.string.FluxGramPluginRiskReasonForbiddenStructure;
        }
        if (id.startsWith("hook.")) {
            return R.string.FluxGramPluginRiskReasonHooks;
        }
        if (id.contains("network")) {
            return R.string.FluxGramPluginRiskReasonNetwork;
        }
        if (id.startsWith("filesystem.") || id.startsWith("storage.cross_")) {
            return R.string.FluxGramPluginRiskReasonFiles;
        }
        if (id.startsWith("process.") || id.startsWith("code.native_")) {
            return R.string.FluxGramPluginRiskReasonProcess;
        }
        if (id.startsWith("bridge.") || id.startsWith("host.")
                || id.startsWith("android.")) {
            return R.string.FluxGramPluginRiskReasonInternals;
        }
        if (id.startsWith("reflection.") || id.startsWith("code.dynamic_")
                || id.startsWith("python.dynamic_")
                || id.startsWith("python.global_")) {
            return R.string.FluxGramPluginRiskReasonDynamicCode;
        }
        if (id.startsWith("thread.") || id.startsWith("lifecycle.")) {
            return R.string.FluxGramPluginRiskReasonLifecycle;
        }
        if (id.startsWith("ui.")) {
            return R.string.FluxGramPluginRiskReasonUi;
        }
        if (id.startsWith("declaration.")) {
            return R.string.FluxGramPluginRiskReasonDeclaration;
        }
        if (id.startsWith("update.")) {
            return R.string.FluxGramPluginRiskReasonStructure;
        }

        return 0;
    }

    private void showRestartInfo() {
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider()).setTitle(LocaleController.getString(R.string.FluxGramPluginRestartRequired)).setMessage(LocaleController.getString(R.string.FluxGramPluginRestartInfo)).setNegativeButton(LocaleController.getString(R.string.OK), null).setPositiveButton(LocaleController.getString(R.string.FluxGramOpenAndroidSettings), (dialog, which) -> fragment.getParentActivity().startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + fragment.getParentActivity().getPackageName())))).create());
    }
}
