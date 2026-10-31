package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.plugins.ExternalPlugins;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.messenger.plugins.packages.PluginManifest;
import org.telegram.messenger.plugins.packages.PluginPackageStore;
import org.telegram.messenger.plugins.risk.PluginRiskPolicy;
import org.telegram.messenger.plugins.signing.PluginSignerTransition;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.FluxGramPluginIconView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RadialProgressView;

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
        view.setTypeface(AndroidUtilities.regular());
        view.setPadding(0, dp(8), 0, dp(8));

        return view;
    }

    private void applyRegularTypeface(View view) {
        if (view instanceof TextView) {
            ((TextView) view).setTypeface(AndroidUtilities.regular());
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                applyRegularTypeface(group.getChildAt(i));
            }
        }
    }

    private void show(ExternalPlugins.Review review) {
        Context context = fragment.getParentActivity();
        PluginManifest manifest = review.result.manifest;
        boolean update = review.installedVersion != null;
        PluginPackageStore.Pending pending = ExternalPlugins.getPending(manifest.id);
        boolean sameVersion = update && manifest.version.equals(review.installedVersion);
        boolean newer = !update || PluginPackageStore.compareVersions(
                manifest.version, review.installedVersion) > 0;
        boolean signerBlocked = review.signerReview.transition.decision
                == PluginSignerTransition.Decision.BLOCK;
        boolean blocked = review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK
                || signerBlocked;
        boolean canInstall = pending == null && newer && !blocked;
        InstallBottomSheet sheet = new InstallBottomSheet(
                context, fragment.getResourceProvider());
        sheet.fixNavigationBar(fragment.getThemedColor(Theme.key_dialogBackground));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(12));

        FrameLayout header = new FrameLayout(context);

        TextView screenTitle = sheetText(
                context, LocaleController.getString(R.string.FluxGramPluginInstallTitle), 27, false);
        screenTitle.setGravity(Gravity.CENTER_VERTICAL);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(TextUtils.TruncateAt.END);
        screenTitle.setPadding(0, 0, 0, 0);
        header.addView(screenTitle, LayoutHelper.createFrameRelatively(
                LayoutHelper.MATCH_PARENT, 64, Gravity.FILL_HORIZONTAL | Gravity.TOP, 4, 0, 64, 0));

        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_ab_close);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setColorFilter(fragment.getThemedColor(Theme.key_dialogTextGray));
        close.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(24),
                Theme.multAlpha(fragment.getThemedColor(Theme.key_dialogTextGray), 0.10f),
                fragment.getThemedColor(Theme.key_listSelector)));
        close.setContentDescription(LocaleController.getString(R.string.Close));
        close.setOnClickListener(view -> sheet.dismiss());
        header.addView(close, LayoutHelper.createFrameRelatively(
                48, 48, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = (int) (windowHeight(context) * 0.85f) - dp(196);
                int limit = Math.min(MeasureSpec.getSize(heightMeasureSpec), available);

                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    limit = available;
                }

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.max(0, limit), MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, dp(16), 0, dp(12));

        LinearLayout pluginRow = new LinearLayout(context);
        pluginRow.setOrientation(LinearLayout.HORIZONTAL);
        pluginRow.setGravity(Gravity.CENTER_VERTICAL);

        FluxGramPluginIconView icon = new FluxGramPluginIconView(
                context, fragment.getResourceProvider(), manifest.id, manifest.name);
        icon.setRoundRadius(dp(18));

        if (review.icon != null) {
            icon.setImageBitmap(review.icon);
        }

        pluginRow.addView(icon, LayoutHelper.createLinear(80, 80, Gravity.CENTER_VERTICAL));

        LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = sheetText(context, manifest.name, 26, false);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, 0, 0, dp(4));
        identity.addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView metadata = sheetText(context,
                manifest.version + "  ·  " + manifest.author, 15, true);
        metadata.setSingleLine(true);
        metadata.setEllipsize(TextUtils.TruncateAt.END);
        metadata.setPadding(0, 0, 0, 0);
        identity.addView(metadata, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        LinearLayout.LayoutParams identityParams = LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL);
        identityParams.setMarginStart(dp(18));
        pluginRow.addView(identity, identityParams);
        details.addView(pluginRow, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        TextView description = sheetText(context, manifest.description, 17, true);
        description.setLineSpacing(dp(3), 1f);
        description.setPadding(0, 0, 0, dp(14));
        details.addView(description, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!canInstall && !blocked) {
            TextView state = sheetText(context, LocaleController.getString(
                    pending != null
                            ? R.string.FluxGramPluginRestartRequired
                            : sameVersion
                                    ? R.string.FluxGramPluginAlreadyInstalled
                                    : R.string.FluxGramPluginNewerVersionRequired), 15, true);
            state.setPadding(0, 0, 0, dp(12));
            details.addView(state);
        }

        if (update && canInstall) {
            TextView version = sheetText(
                    context, review.installedVersion + " → " + manifest.version, 17, false);
            version.setPadding(0, 0, 0, dp(2));
            details.addView(version);

            TextView updateInfo = sheetText(
                    context, LocaleController.getString(R.string.FluxGramPluginUpdateInfo), 14, true);
            updateInfo.setPadding(0, 0, 0, dp(12));
            details.addView(updateInfo);
        }

        View[] openReport = new View[1];
        details.addView(createRiskCard(context, review, update, openReport), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 18));
        details.addView(createFullAccessRow(context), LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        scroll.addView(details);
        content.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        CheckBoxCell enable = new CheckBoxCell(
                context, CheckBoxCell.TYPE_CHECK_BOX_UNKNOWN, fragment.getResourceProvider());
        enable.setText(LocaleController.getString(update
                        ? R.string.FluxGramEnableAfterUpdate : R.string.FluxGramEnableAfterInstall),
                "", update && PluginManager.getInstance().isEnabled(manifest.id), false);
        applyRegularTypeface(enable);
        enable.setEnabled(canInstall);
        enable.setAlpha(canInstall ? 1f : 0.5f);
        enable.setOnClickListener(view -> enable.setChecked(!enable.isChecked(), true));
        enable.setVisibility(blocked ? View.GONE : View.VISIBLE);
        content.addView(enable, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 50, Gravity.START));

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

        PluginRiskReportView reportView = new PluginRiskReportView(
                context, review, fragment.getResourceProvider(), sheet::showSummary, sheet::dismiss);
        InstallStateView stateView = new InstallStateView(context, sheet);
        sheet.setPages(content, reportView, stateView);
        openReport[0].setOnClickListener(view -> sheet.showReport());
        boolean[] installing = {false};
        boolean[] active = {true};
        boolean[] consumed = {false};
        boolean[] confirmed = {false};
        Runnable installAction = () -> {
            if (!active[0] || installing[0] || !canInstall || !isPresentationUsable()) {
                return;
            }

            installing[0] = true;
            consumed[0] = true;
            sheet.setBusy(true);
            install.setEnabled(false);
            enable.setEnabled(false);
            install.setText(LocaleController.getString(R.string.FluxGramPluginInstalling));
            stateView.showInstalling(manifest.name);
            sheet.showState();

            ExternalPlugins.install(
                    review,
                    enable.isChecked(),
                    update ? review.installedVersion : null,
                    confirmed[0],
                    (id, error) -> {
                        installing[0] = false;
                        sheet.setBusy(false);

                        if (!active[0]) {
                            return;
                        }

                        if (!isPresentationUsable()) {
                            sheet.dismiss();
                            return;
                        }

                        if (error != null) {
                            sheet.setTerminal(true);
                            stateView.showError(error.getMessage(), sheet::dismiss);
                        } else {
                            ExternalPlugins.changed();
                            sheet.setTerminal(true);

                            if (update) {
                                stateView.showUpdated(
                                        manifest.name,
                                        () -> {
                                            Intent intent = new Intent(
                                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    android.net.Uri.parse("package:" + context.getPackageName()));
                                            context.startActivity(intent);
                                        },
                                        sheet::dismiss);
                            } else {
                                stateView.showInstalled(manifest.name, sheet::dismiss);
                            }
                        }
                    });
        };

        install.setOnClickListener(view -> {
            if (!active[0] || installing[0] || !canInstall) {
                return;
            }

            if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM
                    || review.signerReview.transition.decision
                            == PluginSignerTransition.Decision.CONFIRM) {
                sheet.setTerminal(false);
                stateView.showConfirmation(
                        update,
                        confirmationMessage(review),
                        sheet::showSummary,
                        () -> {
                            if (sheet.canConfirm()) {
                                confirmed[0] = true;
                                installAction.run();
                            }
                        });
                sheet.showState();
            } else {
                installAction.run();
            }
        });

        if (fragment.showDialog(sheet, dialog -> {
            active[0] = false;

            if (!installing[0] && !consumed[0]) {
                review.close();
            }
        }) == null) {
            review.close();
        }
    }

    private View createRiskCard(
            Context context,
            ExternalPlugins.Review review,
            boolean update,
            View[] openReport) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(16), dp(20), 0);

        int color;
        int titleId;

        if (review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK
                || review.signerReview.transition.decision
                        == PluginSignerTransition.Decision.BLOCK) {
            color = fragment.getThemedColor(Theme.key_text_RedBold);
            titleId = R.string.FluxGramPluginRiskBlocked;
        } else if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM
                || review.signerReview.transition.decision
                        == PluginSignerTransition.Decision.CONFIRM) {
            color = fragment.getThemedColor(Theme.key_avatar_nameInMessageOrange);
            titleId = R.string.FluxGramPluginRiskIssues;
        } else {
            color = fragment.getThemedColor(Theme.key_windowBackgroundWhiteGreenText);
            titleId = update
                    ? R.string.FluxGramPluginRiskUpdateChecked
                    : R.string.FluxGramPluginRiskChecked;
        }

        card.setBackground(Theme.createRoundRectDrawable(
                dp(22),
                Theme.multAlpha(fragment.getThemedColor(Theme.key_dialogTextBlack), 0.06f)));

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        ImageView shield = riskIcon(context, R.drawable.outline_shield_plain_24, color);
        heading.addView(shield, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL));

        TextView title = sheetText(context, LocaleController.getString(titleId), 16, false);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextSize(18);
        title.setPaddingRelative(dp(12), 0, 0, 0);
        heading.addView(title, LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        card.addView(heading, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        List<RiskReason> reasons = riskReasonItems(review.riskPolicy.reasons);
        RiskReason signerReason = signerReason(review.signerReview.transition.reason);

        if (signerReason != null) {
            reasons.add(signerReason);
        }

        if (reasons.isEmpty()) {
            reasons = Collections.singletonList(new RiskReason(
                    riskMessage(review, update), R.drawable.msg_info));
        }

        int count = Math.min(4, reasons.size());

        for (int i = 0; i < count; i++) {
            RiskReason reason = reasons.get(i);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);

            ImageView reasonIcon = riskIcon(
                    context,
                    reason.icon,
                    fragment.getThemedColor(Theme.key_dialogTextGray));
            row.addView(reasonIcon, LayoutHelper.createLinear(26, 26, Gravity.TOP, 0, 3, 0, 0));

            TextView reasonText = sheetText(context, reason.text, 15, false);
            reasonText.setLineSpacing(dp(2), 1f);
            reasonText.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams reasonParams = LayoutHelper.createLinear(
                    0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP);
            reasonParams.setMarginStart(dp(14));
            row.addView(reasonText, reasonParams);
            card.addView(row, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));
        }

        View divider = new View(context);
        divider.setBackgroundColor(fragment.getThemedColor(Theme.key_divider));
        card.addView(divider, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density));

        FrameLayout report = new FrameLayout(context);
        report.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(12), Color.TRANSPARENT, fragment.getThemedColor(Theme.key_listSelector)));
        report.setContentDescription(LocaleController.getString(R.string.FluxGramPluginRiskReportView));

        TextView reportText = sheetText(
                context, LocaleController.getString(R.string.FluxGramPluginRiskReportView), 15, false);
        reportText.setTypeface(AndroidUtilities.bold());
        reportText.setTextColor(fragment.getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        reportText.setGravity(Gravity.CENTER_VERTICAL);
        reportText.setPadding(0, 0, 0, 0);
        report.addView(reportText, LayoutHelper.createFrameRelatively(
                LayoutHelper.MATCH_PARENT, 52, Gravity.FILL_HORIZONTAL | Gravity.TOP, 0, 0, 36, 0));

        ImageView arrow = riskIcon(
                context,
                R.drawable.msg_arrowright,
                fragment.getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        arrow.setScaleX(LocaleController.isRTL ? -1f : 1f);
        report.addView(arrow, LayoutHelper.createFrameRelatively(
                24, 24, Gravity.END | Gravity.CENTER_VERTICAL));
        card.addView(report, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 52));
        openReport[0] = report;

        return card;
    }

    private View createFullAccessRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        ImageView icon = riskIcon(
                context,
                R.drawable.wallet_key,
                fragment.getThemedColor(Theme.key_dialogTextGray));
        LinearLayout.LayoutParams iconParams = LayoutHelper.createLinear(30, 30, Gravity.TOP);
        iconParams.setMarginStart(dp(8));
        iconParams.topMargin = dp(2);
        row.addView(icon, iconParams);

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView title = sheetText(
                context, LocaleController.getString(R.string.FluxGramFullAccess), 17, false);
        title.setTypeface(AndroidUtilities.bold());
        title.setPadding(0, 0, 0, dp(4));
        text.addView(title, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView description = sheetText(
                context, LocaleController.getString(R.string.FluxGramPluginTrust), 15, true);
        description.setLineSpacing(dp(2), 1f);
        description.setPadding(0, 0, 0, 0);
        text.addView(description, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        LinearLayout.LayoutParams textParams = LayoutHelper.createLinear(
                0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP);
        textParams.setMarginStart(dp(14));
        textParams.setMarginEnd(dp(8));
        row.addView(text, textParams);

        return row;
    }

    private ImageView riskIcon(Context context, int resource, int color) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(resource);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(color);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        return icon;
    }

    private int windowHeight(Context context) {
        if (context instanceof Activity) {
            int height = ((Activity) context).getWindow().getDecorView().getHeight();

            if (height > 0) {
                return height;
            }
        }

        return AndroidUtilities.displaySize.y;
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

    private CharSequence confirmationMessage(ExternalPlugins.Review review) {
        StringBuilder message = new StringBuilder();

        if (review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM) {
            message.append(riskMessage(review, false));
        }

        if (review.signerReview.transition.decision
                == PluginSignerTransition.Decision.CONFIRM) {
            if (message.length() > 0) {
                message.append("\n\n");
            }

            message.append(LocaleController.getString(R.string.FluxGramPluginSignerConfirmInfo));
            RiskReason reason = signerReason(review.signerReview.transition.reason);

            if (reason != null) {
                message.append("\n\u2022 ").append(reason.text);
            }
        }

        return message;
    }

    private RiskReason signerReason(PluginSignerTransition.Reason reason) {
        int stringId;

        switch (reason) {
            case PIN_FIRST_IDENTITY:
                stringId = R.string.FluxGramPluginSignerFirstIdentity;
                break;
            case UNSIGNED_PACKAGE_CHANGED:
                stringId = R.string.FluxGramPluginSignerUnsignedChanged;
                break;
            case UPGRADE_UNSIGNED_TO_SIGNED:
                stringId = R.string.FluxGramPluginSignerSignedUpgrade;
                break;
            case VERIFIED_ROTATION:
                stringId = R.string.FluxGramPluginSignerRotated;
                break;
            case INVALID_SIGNATURE:
            case UNSIGNED_DOWNGRADE:
            case KEY_CHANGED:
            case INVALID_ROTATION:
            case PLUGIN_ID_MISMATCH:
                stringId = R.string.FluxGramPluginSignerBlocked;
                break;
            default:
                return null;
        }

        return new RiskReason(LocaleController.getString(stringId), R.drawable.wallet_key);
    }

    private List<CharSequence> riskReasons(List<String> reasonIds) {
        ArrayList<CharSequence> result = new ArrayList<>();

        for (RiskReason reason : riskReasonItems(reasonIds)) {
            result.add(reason.text);
        }

        return result;
    }

    private List<RiskReason> riskReasonItems(List<String> reasonIds) {
        ArrayList<RiskReason> result = new ArrayList<>();
        LinkedHashSet<Integer> known = new LinkedHashSet<>();

        for (String reasonId : reasonIds) {
            int stringId = PluginRiskReportView.reasonString(reasonId);

            if (stringId != 0) {
                if (known.add(stringId)) {
                    result.add(new RiskReason(
                            LocaleController.getString(stringId), riskReasonIcon(stringId)));
                }
            } else {
                result.add(new RiskReason(
                        LocaleController.formatString(
                                R.string.FluxGramPluginRiskReasonUnknown, reasonId),
                        R.drawable.msg_info));
            }
        }

        return result;
    }

    private int riskReasonIcon(int stringId) {
        if (stringId == R.string.FluxGramPluginRiskReasonInternals
                || stringId == R.string.FluxGramPluginRiskReasonFiles
                || stringId == R.string.FluxGramPluginRiskReasonManagedStorage) {
            return R.drawable.msg_filled_storageusage;
        }
        if (stringId == R.string.FluxGramPluginRiskReasonPythonRuntime
                || stringId == R.string.FluxGramPluginRiskReasonDynamicCode
                || stringId == R.string.FluxGramPluginRiskReasonProcess) {
            return R.drawable.menu_feature_code;
        }
        if (stringId == R.string.FluxGramPluginRiskReasonHooks
                || stringId == R.string.FluxGramPluginRiskReasonFullProcess) {
            return R.drawable.msg_permissions;
        }

        return R.drawable.msg_info;
    }

    private final class InstallStateView extends LinearLayout {
        private final Context context;
        private final InstallBottomSheet sheet;

        InstallStateView(Context context, InstallBottomSheet sheet) {
            super(context);
            this.context = context;
            this.sheet = sheet;
            setOrientation(VERTICAL);
            setPadding(dp(20), 0, dp(20), dp(20));
            setBackgroundColor(fragment.getThemedColor(Theme.key_dialogBackground));
        }

        void showConfirmation(
                boolean update,
                CharSequence message,
                Runnable onBack,
                Runnable onConfirm) {
            reset(true, onBack, sheet::dismiss);
            addHeroIcon(R.drawable.outline_shield_plain_24, Theme.key_avatar_nameInMessageOrange);
            addTitle(LocaleController.getString(update
                    ? R.string.FluxGramPluginRiskUpdateConfirmTitle
                    : R.string.FluxGramPluginRiskConfirmTitle));
            addMessage(message);
            addPrimaryButton(
                    LocaleController.getString(R.string.FluxGramPluginRiskConfirmAction),
                    onConfirm);
            addSecondaryButton(LocaleController.getString(R.string.Cancel), onBack);
        }

        void showInstalling(String name) {
            reset(false, null, null);

            RadialProgressView progress = new RadialProgressView(
                    context, fragment.getResourceProvider());
            progress.setSize(dp(42));
            progress.setProgressColor(fragment.getThemedColor(
                    Theme.key_windowBackgroundWhiteBlueText));
            addView(progress, LayoutHelper.createLinear(
                    72, 72, Gravity.CENTER_HORIZONTAL, 0, 42, 0, 18));
            addTitle(LocaleController.getString(R.string.FluxGramPluginInstalling));
            addMessage(name);
        }

        void showInstalled(String name, Runnable onClose) {
            reset(false, null, onClose);
            addHeroIcon(R.drawable.msg_check_s, Theme.key_windowBackgroundWhiteGreenText);
            addTitle(LocaleController.getString(R.string.FluxGramPluginInstalled));
            addMessage(name);
            addPrimaryButton(LocaleController.getString(R.string.Close), onClose);
        }

        void showUpdated(String name, Runnable onOpenSettings, Runnable onClose) {
            reset(false, null, onClose);
            addHeroIcon(R.drawable.msg_check_s, Theme.key_windowBackgroundWhiteGreenText);
            addTitle(LocaleController.getString(R.string.FluxGramPluginRestartRequired));
            addMessage(LocaleController.getString(R.string.FluxGramPluginRestartInfo));
            addPrimaryButton(
                    LocaleController.getString(R.string.FluxGramOpenAndroidSettings),
                    onOpenSettings);
            addSecondaryButton(LocaleController.getString(R.string.Close), onClose);
        }

        void showError(String error, Runnable onClose) {
            reset(false, null, onClose);
            addHeroIcon(R.drawable.msg_warning, Theme.key_text_RedRegular);
            addTitle(LocaleController.getString(R.string.FluxGramPluginError));
            addMessage(TextUtils.isEmpty(error)
                    ? LocaleController.getString(R.string.ErrorOccurred)
                    : error);
            addPrimaryButton(LocaleController.getString(R.string.Close), onClose);
        }

        private void reset(boolean showBack, Runnable onBack, Runnable onClose) {
            removeAllViews();

            FrameLayout header = new FrameLayout(context);

            if (showBack) {
                ImageView back = headerButton(R.drawable.ic_ab_back, R.string.Back, onBack);
                header.addView(back, LayoutHelper.createFrameRelatively(
                        48, 48, Gravity.START | Gravity.CENTER_VERTICAL));
            }

            if (onClose != null) {
                ImageView close = headerButton(R.drawable.ic_ab_close, R.string.Close, onClose);
                header.addView(close, LayoutHelper.createFrameRelatively(
                        48, 48, Gravity.END | Gravity.CENTER_VERTICAL));
            }

            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }

        private ImageView headerButton(int icon, int description, Runnable action) {
            ImageView button = new ImageView(context);
            button.setImageResource(icon);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setColorFilter(fragment.getThemedColor(Theme.key_dialogTextGray));
            button.setBackground(Theme.createSelectorDrawable(
                    fragment.getThemedColor(Theme.key_listSelector),
                    Theme.RIPPLE_MASK_CIRCLE_20DP));
            button.setContentDescription(LocaleController.getString(description));
            button.setOnClickListener(view -> action.run());
            return button;
        }

        private void addHeroIcon(int resource, int colorKey) {
            int tint = fragment.getThemedColor(colorKey);
            FrameLayout container = new FrameLayout(context);
            container.setBackground(Theme.createRoundRectDrawable(
                    dp(28), Theme.multAlpha(tint, 0.12f)));

            ImageView icon = riskIcon(context, resource, tint);
            container.addView(icon, LayoutHelper.createFrame(
                    32, 32, Gravity.CENTER));
            addView(container, LayoutHelper.createLinear(
                    72, 72, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 18));
        }

        private void addTitle(CharSequence value) {
            TextView title = sheetText(context, value, 24, false);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, 0, 0, dp(10));
            addView(title, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        private void addMessage(CharSequence value) {
            TextView message = sheetText(context, value, 15, true);
            message.setGravity(Gravity.CENTER);
            message.setLineSpacing(dp(3), 1f);
            message.setPadding(dp(8), 0, dp(8), dp(28));
            addView(message, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        private void addPrimaryButton(CharSequence text, Runnable action) {
            TextView button = stateButton(text, true, action);
            addView(button, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 50, 0, 0, 0, 8));
        }

        private void addSecondaryButton(CharSequence text, Runnable action) {
            TextView button = stateButton(text, false, action);
            addView(button, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 48));
        }

        private TextView stateButton(CharSequence value, boolean primary, Runnable action) {
            TextView button = sheetText(context, value, 16, false);
            button.setGravity(Gravity.CENTER);
            button.setTypeface(AndroidUtilities.bold());
            button.setPadding(0, 0, 0, 0);
            button.setTextColor(fragment.getThemedColor(primary
                    ? Theme.key_featuredStickers_buttonText
                    : Theme.key_windowBackgroundWhiteBlueText));
            button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    dp(24),
                    primary
                            ? fragment.getThemedColor(Theme.key_featuredStickers_addButton)
                            : Theme.multAlpha(fragment.getThemedColor(
                                    Theme.key_windowBackgroundWhiteBlueText), 0.09f),
                    fragment.getThemedColor(Theme.key_listSelector)));
            button.setOnClickListener(view -> action.run());
            return button;
        }
    }

    private static final class RiskReason {
        final CharSequence text;
        final int icon;

        RiskReason(CharSequence text, int icon) {
            this.text = text;
            this.icon = icon;
        }
    }

    private static final class InstallBottomSheet extends BottomSheet {
        private View summary;
        private View report;
        private View state;
        private boolean reportVisible;
        private boolean stateVisible;
        private boolean busy;
        private boolean terminal;
        private int transitionGeneration;

        InstallBottomSheet(
                Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context, false, resourcesProvider);
        }

        void setPages(View summary, View report, View state) {
            this.summary = summary;
            this.report = report;
            this.state = state;
            report.setVisibility(View.INVISIBLE);
            state.setVisibility(View.INVISIBLE);
            report.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            FrameLayout pages = new FrameLayout(getContext());
            pages.addView(summary, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            pages.addView(report, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            pages.addView(state, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            setCustomView(pages);
        }

        void showReport() {
            if (reportVisible || summary == null || report == null) {
                return;
            }

            reportVisible = true;
            stateVisible = false;
            setAllowNestedScroll(false);
            switchPage(summary, report, true);
            report.announceForAccessibility(LocaleController.getString(
                    R.string.FluxGramPluginRiskReportTitle));
        }

        void showSummary() {
            if ((!reportVisible && !stateVisible)
                    || summary == null
                    || report == null
                    || state == null
                    || busy) {
                return;
            }

            View previous = reportVisible ? report : state;
            reportVisible = false;
            stateVisible = false;
            setAllowNestedScroll(true);
            switchPage(previous, summary, false);
        }

        void showState() {
            if (state == null || busy && stateVisible) {
                return;
            }

            View previous = reportVisible ? report : summary;
            reportVisible = false;
            stateVisible = true;
            setAllowNestedScroll(false);
            switchPage(previous, state, true);
        }

        void setBusy(boolean value) {
            busy = value;
        }

        void setTerminal(boolean value) {
            terminal = value;
        }

        boolean canConfirm() {
            return stateVisible && !busy && !terminal && !isDismissed();
        }

        private void switchPage(View previous, View next, boolean forward) {
            int generation = ++transitionGeneration;
            previous.animate().cancel();
            next.animate().cancel();
            previous.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            next.setVisibility(View.VISIBLE);
            next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);

            if (!SharedConfig.animationsEnabled()) {
                previous.setVisibility(View.INVISIBLE);
                previous.setAlpha(1f);
                previous.setTranslationX(0f);
                previous.setScaleX(1f);
                previous.setScaleY(1f);
                next.setAlpha(1f);
                next.setTranslationX(0f);
                next.setScaleX(1f);
                next.setScaleY(1f);
                return;
            }

            float direction = forward ? 1f : -1f;
            next.setAlpha(0f);
            next.setTranslationX(direction * dp(18));
            next.setScaleX(0.985f);
            next.setScaleY(0.985f);
            next.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start();
            previous.animate()
                    .alpha(0f)
                    .translationX(-direction * dp(10))
                    .scaleX(0.992f)
                    .scaleY(0.992f)
                    .setDuration(170)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> {
                        if (generation != transitionGeneration) {
                            return;
                        }

                        previous.setVisibility(View.INVISIBLE);
                        previous.setAlpha(1f);
                        previous.setTranslationX(0f);
                        previous.setScaleX(1f);
                        previous.setScaleY(1f);
                    })
                    .start();
        }

        @Override
        public void onBackPressed() {
            if (stateVisible && terminal) {
                dismiss();
                return;
            }

            if ((reportVisible || stateVisible) && !busy) {
                showSummary();
                return;
            }

            if (busy) {
                return;
            }

            super.onBackPressed();
        }

        @Override
        protected boolean canDismissWithSwipe() {
            return !reportVisible && !busy && (!stateVisible || terminal);
        }

        @Override
        protected boolean canDismissWithTouchOutside() {
            return !reportVisible && !busy && (!stateVisible || terminal);
        }
    }
}
