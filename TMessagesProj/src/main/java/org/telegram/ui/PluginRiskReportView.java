package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.plugins.ExternalPlugins;
import org.telegram.messenger.plugins.risk.PluginRiskCatalog;
import org.telegram.messenger.plugins.risk.PluginRiskDiff;
import org.telegram.messenger.plugins.risk.PluginRiskEvidence;
import org.telegram.messenger.plugins.risk.PluginRiskReport;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PluginRiskReportView extends LinearLayout {
    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<Item> items = new ArrayList<>();

    PluginRiskReportView(
            Context context,
            ExternalPlugins.Review review,
            Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);

        RecyclerListView list = new RecyclerListView(context, resourcesProvider) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int available = (int) (windowHeight(context) * 0.55f);
                int limit = Math.min(MeasureSpec.getSize(heightMeasureSpec), available);

                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                    limit = available;
                }

                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                        Math.max(0, limit), MeasureSpec.AT_MOST));
            }
        };
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(12), 0, dp(20));
        buildReport(review);
        list.setAdapter(new ReportAdapter());
        addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    static int reasonString(String id) {
        if (!PluginRiskCatalog.resolve(id).known) {
            return 0;
        }
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
        if (id.startsWith("access.full_process.")) {
            return R.string.FluxGramPluginRiskReasonFullProcess;
        }
        if (id.startsWith("python.top_level") || id.equals("python.shared_interpreter")) {
            return R.string.FluxGramPluginRiskReasonPythonRuntime;
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
        if (id.equals("storage.managed")) {
            return R.string.FluxGramPluginRiskReasonManagedStorage;
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
        if (id.equals("ui.managed")) {
            return R.string.FluxGramPluginRiskReasonManagedUi;
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

    private static void performClickHaptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void buildReport(ExternalPlugins.Review review) {
        items.add(Item.header(LocaleController.getString(
                R.string.FluxGramPluginRiskReportAccess)));
        items.add(Item.access(prominentRow(
                LocaleController.getString(R.string.FluxGramFullAccess),
                LocaleController.getString(R.string.FluxGramPluginRiskReportAccessInfo))));

        if (review.installedVersion != null) {
            addUpdateSection(review);
        }

        items.add(Item.header(LocaleController.getString(
                R.string.FluxGramPluginRiskReportFindings)));

        if (review.result.riskReport.evidence.isEmpty()) {
            addRows(rows(row(
                    LocaleController.getString(R.string.FluxGramPluginRiskReportNoFindings),
                    LocaleController.getString(R.string.FluxGramPluginRiskReportNoFindingsInfo))));
        } else {
            items.add(Item.overview(Overview.from(review.result.riskReport.evidence)));
            addEvidenceGroups(review.result.riskReport.evidence);
        }

        ArrayList<Row> declarations = new ArrayList<>();

        for (String capability : review.result.manifest.declaredCapabilities) {
            declarations.add(row(capability, LocaleController.getString(
                    R.string.FluxGramPluginRiskReportDeclaredByAuthor)));
        }

        if (declarations.isEmpty()) {
            declarations.add(row(
                    LocaleController.getString(R.string.FluxGramPluginRiskReportNothingDeclared),
                    LocaleController.getString(R.string.FluxGramPluginRiskReportDeclarationInfo)));
        } else {
            declarations.add(row(
                    LocaleController.getString(R.string.FluxGramPluginRiskReportDeclarationNotice),
                    LocaleController.getString(R.string.FluxGramPluginRiskReportDeclarationInfo)));
        }

        addSection(
                LocaleController.getString(R.string.FluxGramDeclaredCapabilities),
                declarations);

        ArrayList<Row> limitations = new ArrayList<>();

        for (String limitation : review.result.riskReport.limitations) {
            limitations.add(reasonRow(limitation));
        }

        if (limitations.isEmpty()) {
            limitations.add(row(
                    LocaleController.getString(R.string.FluxGramPluginRiskReportNoLimitations),
                    LocaleController.getString(R.string.FluxGramPluginRiskReportNoLimitationsInfo)));
        }

        addSection(
                LocaleController.getString(R.string.FluxGramPluginRiskReportLimitations),
                limitations);

        ArrayList<Row> modules = new ArrayList<>();

        for (PluginRiskReport.ModuleDigest module : review.result.riskReport.modules) {
            modules.add(row(module.role, module.sha256));
        }

        addSection(
                LocaleController.getString(R.string.FluxGramPluginRiskReportModules),
                modules);

        PluginRiskReport report = review.result.riskReport;
        ArrayList<Row> technical = new ArrayList<>();
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginRiskReportPluginId), report.pluginId));
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginVersion), report.pluginVersion));
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginRiskReportArchiveDigest), report.archiveSha256));
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginRiskReportPackageSchema), String.valueOf(report.packageSchemaVersion)));
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginRiskReportAnalyzerVersion), String.valueOf(report.analyzerVersion)));
        technical.add(row(LocaleController.getString(R.string.FluxGramPluginRiskReportHostFingerprint), report.hostFingerprint));
        technical.add(row(
                LocaleController.getString(R.string.FluxGramPluginRiskReportAnalyzedAt),
                report.analyzedAtMillis == 0
                        ? LocaleController.getString(R.string.FluxGramPluginRiskReportUnavailable)
                        : LocaleController.formatDateTime(report.analyzedAtMillis / 1000L, false)));
        technical.add(row(
                LocaleController.getString(R.string.FluxGramPluginRiskReportMethod),
                LocaleController.getString(R.string.FluxGramPluginRiskReportMethodInfo)));
        addSection(
                LocaleController.getString(R.string.FluxGramPluginRiskReportTechnical),
                technical);
    }

    private void addUpdateSection(ExternalPlugins.Review review) {
        ArrayList<Row> changes = new ArrayList<>();

        if (review.previousRiskReport == null) {
            changes.add(row(
                    LocaleController.getString(R.string.FluxGramPluginRiskReportComparisonUnavailable),
                    LocaleController.getString(R.string.FluxGramPluginRiskReasonBaseline)));
        } else {
            PluginRiskDiff diff = review.riskDiff;
            boolean[] removedUsed = new boolean[diff.removedEvidence.size()];

            for (PluginRiskEvidence evidence : diff.addedEvidence) {
                int changedIndex = matchingEvidence(diff.removedEvidence, removedUsed, evidence);

                if (changedIndex >= 0) {
                    removedUsed[changedIndex] = true;
                    changes.add(row(
                            LocaleController.formatString(
                                    R.string.FluxGramPluginRiskReportEvidenceChanged,
                                    evidenceTitle(evidence.id)),
                            evidenceMetadata(evidence)));
                } else {
                    changes.add(row(
                            LocaleController.formatString(
                                    R.string.FluxGramPluginRiskReportAdded,
                                    evidenceTitle(evidence.id)),
                            evidenceMetadata(evidence)));
                }
            }

            for (int i = 0; i < diff.removedEvidence.size(); i++) {
                if (removedUsed[i]) {
                    continue;
                }

                PluginRiskEvidence evidence = diff.removedEvidence.get(i);
                changes.add(row(
                        LocaleController.formatString(
                                R.string.FluxGramPluginRiskReportRemoved,
                                evidenceTitle(evidence.id)),
                        evidenceMetadata(evidence)));
            }
            for (PluginRiskReport.ModuleDigest module : diff.addedModules) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportModuleAdded),
                        module.role));
            }
            for (PluginRiskReport.ModuleDigest module : diff.removedModules) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportModuleRemoved),
                        module.role));
            }
            for (String role : diff.changedModuleDigests) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportModuleChanged),
                        role));
            }
            for (String limitation : diff.addedLimitations) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportLimitationAdded),
                        limitation));
            }
            for (String limitation : diff.removedLimitations) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportLimitationRemoved),
                        limitation));
            }
            if (diff.packageSchemaChanged) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportSchemaChanged),
                        LocaleController.formatString(
                                R.string.FluxGramPluginRiskReportVersionChange,
                                review.previousRiskReport.packageSchemaVersion,
                                review.result.riskReport.packageSchemaVersion)));
            }
            if (diff.analyzerVersionChanged) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportAnalyzerChanged),
                        LocaleController.formatString(
                                R.string.FluxGramPluginRiskReportVersionChange,
                                review.previousRiskReport.analyzerVersion,
                                review.result.riskReport.analyzerVersion)));
            }
            if (diff.hostFingerprintChanged) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportContextChanged),
                        LocaleController.getString(R.string.FluxGramPluginRiskReportContextChangedInfo)));
            }
            if (changes.isEmpty()) {
                changes.add(row(
                        LocaleController.getString(R.string.FluxGramPluginRiskReportNoNewRisks),
                        LocaleController.getString(R.string.FluxGramPluginRiskReportCodeChanged)));
            }
        }

        addSection(
                LocaleController.getString(R.string.FluxGramPluginRiskReportChanges),
                changes);
    }

    private int matchingEvidence(
            List<PluginRiskEvidence> removed,
            boolean[] used,
            PluginRiskEvidence added) {
        for (int i = 0; i < removed.size(); i++) {
            PluginRiskEvidence candidate = removed.get(i);

            if (!used[i]
                    && candidate.id.equals(added.id)
                    && candidate.source == added.source) {
                return i;
            }
        }

        return -1;
    }

    private Row evidenceRow(PluginRiskEvidence evidence) {
        return row(evidenceTitle(evidence.id), evidenceMetadata(evidence));
    }

    private void addEvidenceGroups(List<PluginRiskEvidence> evidenceList) {
        LinkedHashMap<CharSequence, EvidenceGroup> groups = new LinkedHashMap<>();

        for (PluginRiskEvidence evidence : evidenceList) {
            CharSequence title = evidenceTitle(evidence.id);
            EvidenceGroup group = groups.get(title);

            if (group == null) {
                group = new EvidenceGroup(title);
                groups.put(title, group);
            }

            group.add(evidence, PluginRiskCatalog.resolve(evidence.id).severity);
        }

        boolean first = true;

        for (Map.Entry<CharSequence, EvidenceGroup> entry : groups.entrySet()) {
            EvidenceGroup group = entry.getValue();
            group.expanded = first;
            items.add(Item.group(group));
            first = false;
        }
    }

    private Row reasonRow(String id) {
        return row(evidenceTitle(id), id);
    }

    private CharSequence evidenceTitle(String id) {
        if (!PluginRiskCatalog.resolve(id).known) {
            return LocaleController.formatString(R.string.FluxGramPluginRiskReasonUnknown, id);
        }

        int stringId = reasonString(id);

        if (stringId != 0) {
            return LocaleController.getString(stringId);
        }

        return LocaleController.formatString(R.string.FluxGramPluginRiskReasonUnknown, id);
    }

    private CharSequence evidenceMetadata(PluginRiskEvidence evidence) {
        PluginRiskCatalog.Descriptor descriptor = PluginRiskCatalog.resolve(evidence.id);
        String metadata = LocaleController.formatString(
                R.string.FluxGramPluginRiskReportEvidenceMetadata,
                severity(descriptor.severity),
                source(evidence.source),
                confidence(evidence.confidence),
                evidence.id);
        String safeDetail = safeEvidenceDetail(evidence);

        if (safeDetail == null) {
            return metadata;
        }

        return metadata + "\n" + LocaleController.formatString(
                R.string.FluxGramPluginRiskReportKnownHost, safeDetail);
    }

    private String safeEvidenceDetail(PluginRiskEvidence evidence) {
        if (!evidence.id.equals("network.endpoint_literal")
                || evidence.detail == null
                || evidence.detail.isEmpty()) {
            return null;
        }

        String value = evidence.detail.contains("://")
                ? evidence.detail
                : "https://" + evidence.detail;
        String host = Uri.parse(value).getHost();

        return host == null || host.isEmpty() ? null : host;
    }

    private String severity(PluginRiskCatalog.Severity severity) {
        switch (severity) {
            case CRITICAL:
                return LocaleController.getString(R.string.FluxGramPluginRiskSeverityCritical);
            case HIGH:
                return LocaleController.getString(R.string.FluxGramPluginRiskSeverityHigh);
            case ELEVATED:
                return LocaleController.getString(R.string.FluxGramPluginRiskSeverityElevated);
            default:
                return LocaleController.getString(R.string.FluxGramPluginRiskSeverityInformational);
        }
    }

    private String source(PluginRiskEvidence.Source source) {
        switch (source) {
            case MANIFEST:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourceManifest);
            case ARCHIVE:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourceArchive);
            case DEX:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourceDex);
            case PYTHON:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourcePython);
            case RUNTIME:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourceRuntime);
            default:
                return LocaleController.getString(R.string.FluxGramPluginRiskSourceReputation);
        }
    }

    private String confidence(PluginRiskEvidence.Confidence confidence) {
        switch (confidence) {
            case EXACT:
                return LocaleController.getString(R.string.FluxGramPluginRiskConfidenceExact);
            case HEURISTIC:
                return LocaleController.getString(R.string.FluxGramPluginRiskConfidenceHeuristic);
            default:
                return LocaleController.getString(R.string.FluxGramPluginRiskConfidenceUnknown);
        }
    }

    private void addSection(CharSequence title, List<Row> rows) {
        items.add(Item.header(title));

        addRows(rows);
    }

    private void addRows(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            items.add(Item.row(rows.get(i), i == 0, i == rows.size() - 1));
        }
    }

    private TextView text(CharSequence value, int size, boolean secondary) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color(secondary
                ? Theme.key_dialogTextGray
                : Theme.key_dialogTextBlack));
        view.setTypeface(AndroidUtilities.regular());

        return view;
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
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

    private static ArrayList<Row> rows(Row row) {
        ArrayList<Row> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }

    private static Row row(CharSequence title, CharSequence detail) {
        return new Row(title, detail, false);
    }

    private static Row prominentRow(CharSequence title, CharSequence detail) {
        return new Row(title, detail, true);
    }

    private static final class Row {
        final CharSequence title;
        final CharSequence detail;
        final boolean prominent;

        Row(CharSequence title, CharSequence detail, boolean prominent) {
            this.title = title;
            this.detail = detail;
            this.prominent = prominent;
        }
    }

    private static final class Item {
        static final int HEADER = 0;
        static final int ROW = 1;
        static final int GROUP = 2;
        static final int ACCESS = 3;
        static final int OVERVIEW = 4;

        final int type;
        final CharSequence header;
        final Row row;
        final EvidenceGroup group;
        final Overview overview;
        final boolean first;
        final boolean last;

        private Item(
                int type,
                CharSequence header,
                Row row,
                EvidenceGroup group,
                Overview overview,
                boolean first,
                boolean last) {
            this.type = type;
            this.header = header;
            this.row = row;
            this.group = group;
            this.overview = overview;
            this.first = first;
            this.last = last;
        }

        static Item header(CharSequence title) {
            return new Item(HEADER, title, null, null, null, false, false);
        }

        static Item row(Row row, boolean first, boolean last) {
            return new Item(ROW, null, row, null, null, first, last);
        }

        static Item group(EvidenceGroup group) {
            return new Item(GROUP, null, null, group, null, false, false);
        }

        static Item access(Row row) {
            return new Item(ACCESS, null, row, null, null, true, true);
        }

        static Item overview(Overview overview) {
            return new Item(OVERVIEW, null, null, null, overview, false, false);
        }
    }

    private static final class Overview {
        int critical;
        int high;
        int elevated;
        int informational;

        static Overview from(List<PluginRiskEvidence> evidence) {
            Overview overview = new Overview();

            for (PluginRiskEvidence item : evidence) {
                switch (PluginRiskCatalog.resolve(item.id).severity) {
                    case CRITICAL:
                        overview.critical++;
                        break;
                    case HIGH:
                        overview.high++;
                        break;
                    case ELEVATED:
                        overview.elevated++;
                        break;
                    default:
                        overview.informational++;
                        break;
                }
            }

            return overview;
        }
    }

    private static final class EvidenceGroup {
        final CharSequence title;
        final ArrayList<PluginRiskEvidence> evidence = new ArrayList<>();
        PluginRiskCatalog.Severity severity = PluginRiskCatalog.Severity.INFORMATIONAL;
        boolean expanded;

        EvidenceGroup(CharSequence title) {
            this.title = title;
        }

        void add(PluginRiskEvidence value, PluginRiskCatalog.Severity valueSeverity) {
            evidence.add(value);

            if (valueSeverity.ordinal() > severity.ordinal()) {
                severity = valueSeverity;
            }
        }
    }

    private final class ReportAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == Item.GROUP;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == Item.HEADER) {
                TextView header = text("", 15, true);
                header.setTypeface(AndroidUtilities.bold());
                header.setTextColor(color(Theme.key_windowBackgroundWhiteBlueHeader));
                header.setPadding(dp(20), dp(14), dp(20), dp(8));
                ViewCompat.setAccessibilityHeading(header, true);
                return new RecyclerListView.Holder(header);
            }

            if (viewType == Item.GROUP) {
                EvidenceGroupView group = new EvidenceGroupView(parent.getContext());
                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(16);
                params.rightMargin = dp(16);
                params.bottomMargin = dp(10);
                group.setLayoutParams(params);
                return new RecyclerListView.Holder(group);
            }

            if (viewType == Item.OVERVIEW) {
                RiskOverviewView overview = new RiskOverviewView(parent.getContext());
                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(16);
                params.rightMargin = dp(16);
                params.bottomMargin = dp(10);
                overview.setLayoutParams(params);
                return new RecyclerListView.Holder(overview);
            }

            ReportRowView row = new ReportRowView(parent.getContext());
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(16);
            params.rightMargin = dp(16);
            params.bottomMargin = viewType == Item.ACCESS ? dp(10) : 0;
            row.setLayoutParams(params);

            return new RecyclerListView.Holder(row);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);

            if (item.type == Item.HEADER) {
                ((TextView) holder.itemView).setText(item.header);
            } else if (item.type == Item.GROUP) {
                ((EvidenceGroupView) holder.itemView).bind(item.group);
            } else if (item.type == Item.OVERVIEW) {
                ((RiskOverviewView) holder.itemView).bind(item.overview);
            } else {
                ((ReportRowView) holder.itemView).bind(item);
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            super.onViewAttachedToWindow(holder);

            if (holder.getItemViewType() == Item.GROUP) {
                holder.itemView.setOnClickListener(view -> {
                    int position = holder.getAdapterPosition();

                    if (position == RecyclerView.NO_POSITION) {
                        return;
                    }

                    performClickHaptic(view);
                    Item item = items.get(position);
                    item.group.expanded = !item.group.expanded;
                    ((EvidenceGroupView) holder.itemView).setExpanded(
                            item.group.expanded, true);
                });
            }
        }

        @Override
        public void onViewDetachedFromWindow(RecyclerView.ViewHolder holder) {
            holder.itemView.setOnClickListener(null);

            if (holder.getItemViewType() == Item.GROUP) {
                ((EvidenceGroupView) holder.itemView).cancelAnimation();
            }

            super.onViewDetachedFromWindow(holder);
        }
    }

    private final class RiskOverviewView extends LinearLayout {
        private final TextView high;
        private final TextView elevated;
        private final TextView informational;

        RiskOverviewView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(6), dp(6), dp(6), dp(6));

            high = overviewChip(context);
            elevated = overviewChip(context);
            informational = overviewChip(context);
            addView(high, chipParams());
            addView(elevated, chipParams());
            addView(informational, chipParams());
        }

        void bind(Overview overview) {
            bindChip(
                    high,
                    LocaleController.getString(R.string.FluxGramPluginRiskSeverityHigh),
                    overview.critical + overview.high,
                    color(Theme.key_text_RedRegular));
            bindChip(
                    elevated,
                    LocaleController.getString(R.string.FluxGramPluginRiskSeverityElevated),
                    overview.elevated,
                    color(Theme.key_color_orange));
            bindChip(
                    informational,
                    LocaleController.getString(R.string.FluxGramPluginRiskSeverityInformational),
                    overview.informational,
                    color(Theme.key_windowBackgroundWhiteBlueHeader));
        }

        private TextView overviewChip(Context context) {
            TextView view = text("", 13, false);
            view.setGravity(Gravity.CENTER);
            view.setTypeface(AndroidUtilities.bold());
            view.setPadding(dp(8), dp(10), dp(8), dp(10));
            return view;
        }

        private LinearLayout.LayoutParams chipParams() {
            LinearLayout.LayoutParams params = LayoutHelper.createLinear(
                    0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL);
            params.setMarginStart(dp(3));
            params.setMarginEnd(dp(3));
            return params;
        }

        private void bindChip(TextView view, String label, int count, int tint) {
            view.setText(count + "\n" + label);
            view.setTextColor(tint);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.multAlpha(tint, 0.10f));
            background.setStroke(dp(1), Theme.multAlpha(tint, 0.20f));
            background.setCornerRadius(dp(14));
            view.setBackground(background);
        }
    }

    private final class EvidenceGroupView extends LinearLayout {
        private final TextView severity;
        private final TextView title;
        private final TextView count;
        private final ImageView arrow;
        private final LinearLayout expanded;
        private final TextView details;
        private ValueAnimator expandAnimator;

        EvidenceGroupView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(16), dp(16), dp(16));

            severity = text("", 13, false);
            addView(severity, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

            FrameLayout heading = new FrameLayout(context);
            title = text("", 18, false);
            title.setTypeface(AndroidUtilities.bold());
            heading.addView(title, LayoutHelper.createFrameRelatively(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT,
                    Gravity.START | Gravity.TOP,
                    0,
                    0,
                    40,
                    0));

            arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.arrow_more);
            arrow.setColorFilter(color(Theme.key_dialogTextGray));
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            arrow.setBackground(Theme.createSelectorDrawable(
                    color(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            heading.addView(arrow, LayoutHelper.createFrameRelatively(
                    32, 32, Gravity.END | Gravity.TOP));
            addView(heading, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            count = text("", 15, true);
            addView(count, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

            expanded = new LinearLayout(context);
            expanded.setOrientation(VERTICAL);

            TextView explanation = text(
                    LocaleController.getString(R.string.FluxGramPluginRiskGroupInfo),
                    15,
                    true);
            explanation.setLineSpacing(dp(3), 1f);
            expanded.addView(explanation, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 14));

            details = text("", 14, true);
            details.setTextIsSelectable(true);
            details.setLineSpacing(dp(2), 1f);
            expanded.addView(details, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(expanded, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        }

        void bind(EvidenceGroup group) {
            cancelAnimation();
            int tint = severityColor(group.severity);
            severity.setText(severity(group.severity));
            severity.setTextColor(tint);
            severity.setTypeface(AndroidUtilities.bold());
            severity.setPadding(dp(10), dp(5), dp(10), dp(5));
            GradientDrawable severityBackground = new GradientDrawable();
            severityBackground.setColor(Theme.multAlpha(tint, 0.12f));
            severityBackground.setCornerRadius(dp(10));
            severity.setBackground(severityBackground);
            title.setText(group.title);
            count.setText(LocaleController.formatString(
                    R.string.FluxGramPluginRiskSignals, group.evidence.size()));
            setExpanded(group.expanded, false);

            StringBuilder value = new StringBuilder();

            for (PluginRiskEvidence evidence : group.evidence) {
                if (value.length() > 0) {
                    value.append("\n\n");
                }

                value.append(evidenceMetadata(evidence));
            }

            details.setText(value);
            setContentDescription(group.title + ". " + count.getText());

            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.multAlpha(tint, 0.055f));
            background.setStroke(dp(1), Theme.multAlpha(tint, 0.18f));
            background.setCornerRadius(dp(18));
            setBackground(background);
        }

        void setExpanded(boolean value, boolean animated) {
            cancelAnimation();
            arrow.animate().cancel();

            if (!animated || !SharedConfig.animationsEnabled()) {
                expanded.setVisibility(value ? VISIBLE : GONE);
                expanded.setAlpha(1f);
                expanded.setTranslationY(0f);
                expanded.getLayoutParams().height = LayoutHelper.WRAP_CONTENT;
                arrow.setRotation(value ? 180f : 0f);
                return;
            }

            arrow.animate()
                    .rotation(value ? 180f : 0f)
                    .setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .start();

            if (value) {
                expanded.setVisibility(VISIBLE);
                expanded.getLayoutParams().height = LayoutHelper.WRAP_CONTENT;
                expanded.measure(
                        MeasureSpec.makeMeasureSpec(
                                Math.max(0, getMeasuredWidth() - getPaddingLeft() - getPaddingRight()),
                                MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int target = expanded.getMeasuredHeight();
                expanded.getLayoutParams().height = 0;
                expanded.setAlpha(0f);
                expanded.setTranslationY(-dp(8));
                expandAnimator = ValueAnimator.ofInt(0, target);
            } else {
                int start = expanded.getHeight();
                expandAnimator = ValueAnimator.ofInt(start, 0);
            }

            expandAnimator.setDuration(240);
            expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            expandAnimator.addUpdateListener(animator -> {
                int height = (Integer) animator.getAnimatedValue();
                expanded.getLayoutParams().height = height;
                expanded.setAlpha(value
                        ? animator.getAnimatedFraction()
                        : 1f - animator.getAnimatedFraction());
                expanded.setTranslationY(value
                        ? -dp(8) * (1f - animator.getAnimatedFraction())
                        : -dp(4) * animator.getAnimatedFraction());
                expanded.requestLayout();
            });
            expandAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    expanded.setVisibility(value ? VISIBLE : GONE);
                    expanded.setAlpha(1f);
                    expanded.setTranslationY(0f);
                    expanded.getLayoutParams().height = LayoutHelper.WRAP_CONTENT;
                    expandAnimator = null;
                }
            });
            expandAnimator.start();
        }

        void cancelAnimation() {
            if (expandAnimator != null) {
                expandAnimator.cancel();
                expandAnimator = null;
            }

            arrow.animate().cancel();
        }

        private int severityColor(PluginRiskCatalog.Severity value) {
            switch (value) {
                case CRITICAL:
                case HIGH:
                    return color(Theme.key_text_RedRegular);
                case ELEVATED:
                    return color(Theme.key_color_orange);
                default:
                    return color(Theme.key_windowBackgroundWhiteBlueHeader);
            }
        }
    }

    private final class ReportRowView extends LinearLayout {
        private final TextView title;
        private final TextView detail;
        private final View divider;

        ReportRowView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(14), dp(10), dp(14), 0);

            title = text("", 15, false);
            title.setTypeface(AndroidUtilities.bold());
            title.setPadding(0, 0, 0, dp(3));
            addView(title, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            detail = text("", 13, true);
            detail.setTextIsSelectable(true);
            addView(detail, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            divider = new View(context);
            divider.setBackgroundColor(color(Theme.key_divider));
            addView(divider, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 1f / AndroidUtilities.density));
        }

        void bind(Item item) {
            title.setText(item.row.title);
            detail.setText(item.row.detail);
            title.setTextSize(item.row.prominent ? 21 : 15);
            detail.setTextSize(item.row.prominent ? 16 : 13);
            detail.setLineSpacing(item.row.prominent ? dp(4) : 0, 1f);
            divider.setVisibility(item.last ? GONE : VISIBLE);

            if (item.type == Item.ACCESS) {
                setPadding(dp(18), dp(18), dp(18), dp(18));
                divider.setVisibility(GONE);
                GradientDrawable accessBackground = new GradientDrawable();
                int tint = color(Theme.key_windowBackgroundWhiteBlueHeader);
                accessBackground.setColor(Theme.multAlpha(tint, 0.10f));
                accessBackground.setStroke(dp(1), Theme.multAlpha(tint, 0.22f));
                accessBackground.setCornerRadius(dp(20));
                setBackground(accessBackground);
                return;
            }

            setPadding(dp(14), dp(10), dp(14), 0);
            float radius = dp(14);
            float[] radii = new float[]{
                    item.first ? radius : 0,
                    item.first ? radius : 0,
                    item.first ? radius : 0,
                    item.first ? radius : 0,
                    item.last ? radius : 0,
                    item.last ? radius : 0,
                    item.last ? radius : 0,
                    item.last ? radius : 0
            };
            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.multAlpha(color(Theme.key_dialogTextBlack), 0.05f));
            background.setCornerRadii(radii);
            setBackground(background);
        }
    }
}
