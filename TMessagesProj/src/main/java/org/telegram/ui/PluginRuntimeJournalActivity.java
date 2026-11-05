package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.Plugin;
import org.telegram.messenger.plugins.PluginManager;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCatalog;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalCoordinator;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournalExport;
import org.telegram.messenger.plugins.journal.PluginRuntimeJournals;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PluginRuntimeJournalSummaryCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.fluxgram.FluxGramSettingsSections;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PluginRuntimeJournalActivity extends BaseFragment {
    private static final int REFRESH = 1;
    private static final int RETRY = 2;
    private static final int STATUS = 3;

    private UniversalRecyclerView listView;
    private PluginRuntimeJournalExport journal;
    private boolean loading;
    private boolean loadFailed;
    private boolean destroyed;
    private int requestGeneration;

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.FluxGramPluginJournal));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == REFRESH) {
                    loadJournal();
                }
            }
        });
        actionBar.createMenu().addItemWithWidth(
                REFRESH,
                R.drawable.msg_retry,
                AndroidUtilities.dp(56),
                LocaleController.getString(R.string.Refresh));

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = content;

        listView = new UniversalRecyclerView(
                this,
                (items, adapter) -> fillItems(items),
                this::onItemClick,
                null);
        listView.setItemAnimator(null);
        listView.adapter.setApplyBackground(false);
        FluxGramSettingsSections.apply(listView, currentAccount);
        content.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        loadJournal();

        return fragmentView;
    }

    @Override
    public void clearViews() {
        requestGeneration++;
        loading = false;
        listView = null;
        super.clearViews();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        requestGeneration++;
        super.onFragmentDestroy();
    }

    private void loadJournal() {
        if (loading || destroyed) {
            return;
        }

        loading = true;
        loadFailed = false;
        int request = ++requestGeneration;
        updateList();
        PluginRuntimeJournalCoordinator.ExportRequestResult result = PluginRuntimeJournals.export(
                (status, export) -> AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || request != requestGeneration) {
                        return;
                    }

                    loading = false;
                    loadFailed = status != PluginRuntimeJournalCoordinator.ExportStatus.EXPORTED
                            || export == null;

                    if (!loadFailed) {
                        journal = export;
                    }

                    updateList();
                }));

        if (result != PluginRuntimeJournalCoordinator.ExportRequestResult.QUEUED) {
            loading = false;
            loadFailed = true;
            updateList();
        }
    }

    private void updateList() {
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    private void fillItems(ArrayList<UItem> items) {
        if (loadFailed) {
            items.add(UItem.asButton(
                    RETRY,
                    R.drawable.msg_retry,
                    LocaleController.getString(R.string.FluxGramPluginJournalUnavailable)));
            items.add(UItem.asShadow(LocaleController.getString(
                    R.string.FluxGramPluginJournalUnavailableInfo)));

            if (journal == null) {
                return;
            }
        }

        if (journal == null) {
            items.add(UItem.asButton(
                    STATUS,
                    LocaleController.getString(R.string.Loading),
                    null));
            items.add(UItem.asShadow(null));
            return;
        }

        if (journal.events.isEmpty()) {
            items.add(UItem.asButton(
                    STATUS,
                    R.drawable.msg_log,
                    LocaleController.getString(R.string.FluxGramPluginJournalEmpty)));
            items.add(UItem.asShadow(LocaleController.getString(
                    R.string.FluxGramPluginJournalEmptyInfo)));
            return;
        }

        LinkedHashMap<String, ArrayList<PluginRuntimeJournalExport.Entry>> groups =
                new LinkedHashMap<>();

        for (int index = journal.events.size() - 1; index >= 0; index--) {
            PluginRuntimeJournalExport.Entry entry = journal.events.get(index);
            ArrayList<PluginRuntimeJournalExport.Entry> entries = groups.get(entry.pluginId);

            if (entries == null) {
                entries = new ArrayList<>();
                groups.put(entry.pluginId, entries);
            }

            entries.add(entry);
        }

        for (Map.Entry<String, ArrayList<PluginRuntimeJournalExport.Entry>> group
                : groups.entrySet()) {
            String pluginId = group.getKey();
            ArrayList<PluginRuntimeJournalExport.Entry> entries = group.getValue();
            Plugin plugin = PluginManager.getInstance().getPlugin(pluginId);
            String name = plugin == null ? pluginId : plugin.getName();
            int starts = 0;
            int stops = 0;
            int errors = 0;

            for (PluginRuntimeJournalExport.Entry entry : entries) {
                if (entry.kind == PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING) {
                    starts++;
                } else if (entry.kind
                        == PluginRuntimeJournalCatalog.Kind.CLEANUP_UNLOAD_SUCCEEDED) {
                    stops++;
                }
                if (isFailure(entry)) {
                    errors++;
                }
            }

            PluginRuntimeJournalExport.Entry latest = entries.get(0);
            CharSequence status = pluginStatus(pluginId, latest);
            CharSequence details = errors == 0
                    ? LocaleController.formatString(
                            R.string.FluxGramPluginJournalSummary,
                            starts,
                            stops)
                    : LocaleController.formatString(
                            R.string.FluxGramPluginJournalSummaryWithErrors,
                            starts,
                            stops,
                            errors);
            items.add(PluginRuntimeJournalSummaryCell.Factory.of(
                    plugin,
                    pluginId,
                    name,
                    status,
                    details,
                    statusColor(pluginId, latest)));

            items.add(UItem.asShadow(null));
        }
    }

    private CharSequence pluginStatus(
            String pluginId,
            PluginRuntimeJournalExport.Entry latest) {
        if (latest.kind == PluginRuntimeJournalCatalog.Kind.CLEANUP_UNLOAD_FAILED) {
            return LocaleController.getString(R.string.FluxGramPluginJournalUnloadFailed);
        }
        PluginManager manager = PluginManager.getInstance();
        if (!manager.isSystemEnabled()) {
            return LocaleController.getString(R.string.FluxGramPluginJournalSystemDisabled);
        }
        return LocaleController.getString(manager.isEnabled(pluginId)
                ? R.string.FluxGramPluginJournalEnabled
                : R.string.FluxGramPluginJournalDisabled);
    }

    private int statusColor(
            String pluginId,
            PluginRuntimeJournalExport.Entry latest) {
        if (isFailure(latest)) {
            return getThemedColor(Theme.key_text_RedRegular);
        }
        PluginManager manager = PluginManager.getInstance();
        if (manager.isSystemEnabled() && manager.isEnabled(pluginId)) {
            return getThemedColor(Theme.key_windowBackgroundWhiteGreenText);
        }
        return getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
    }

    private boolean isFailure(PluginRuntimeJournalExport.Entry entry) {
        String name = entry.kind.name();
        return name.contains("FAILED")
                || name.contains("REJECTED")
                || entry.category == PluginRuntimeJournalCatalog.Category.QUARANTINE
                || entry.category == PluginRuntimeJournalCatalog.Category.CRASH;
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == RETRY) {
            loadJournal();
        }
    }
}
