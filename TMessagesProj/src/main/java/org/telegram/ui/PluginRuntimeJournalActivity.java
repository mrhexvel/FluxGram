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
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PluginRuntimeJournalEventCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.fluxgram.FluxGramSettingsSections;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginRuntimeJournalActivity extends BaseFragment {
    private static final int REFRESH = 1;
    private static final int RETRY = 2;
    private static final int SUMMARY = 3;
    private static final int STATUS = 4;

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
        addSectionLabel(items, R.string.FluxGramPluginJournalScope);

        if (journal == null) {
            items.add(UItem.asButton(
                    SUMMARY,
                    R.drawable.msg_log,
                    LocaleController.getString(R.string.FluxGramPluginJournalScopeTitle),
                    loading
                            ? LocaleController.getString(R.string.Loading)
                            : LocaleController.getString(R.string.FluxGramPluginJournalNoSnapshot)));
        } else {
            items.add(UItem.asButton(
                    SUMMARY,
                    R.drawable.msg_log,
                    LocaleController.getString(R.string.FluxGramPluginJournalScopeTitle),
                    LocaleController.formatString(
                            R.string.FluxGramPluginJournalStats,
                            journal.events.size(),
                            journal.droppedEvents,
                            journal.evictedEvents)));
        }

        items.add(UItem.asShadow(LocaleController.getString(
                R.string.FluxGramPluginJournalScopeInfo)));

        if (loadFailed) {
            addSectionLabel(items, R.string.FluxGramPluginJournalStatus);
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
            addSectionLabel(items, R.string.FluxGramPluginJournalRecent);
            items.add(UItem.asButton(
                    STATUS,
                    LocaleController.getString(R.string.Loading),
                    null));
            items.add(UItem.asShadow(null));
            return;
        }

        if (journal.events.isEmpty()) {
            addSectionLabel(items, R.string.FluxGramPluginJournalRecent);
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
            List<PluginRuntimeJournalExport.Entry> entries = group.getValue();
            Plugin plugin = PluginManager.getInstance().getPlugin(pluginId);
            String title = plugin == null ? pluginId : plugin.getName();
            addSectionLabel(items, title);

            for (int index = 0; index < entries.size(); index++) {
                PluginRuntimeJournalExport.Entry entry = entries.get(index);
                items.add(PluginRuntimeJournalEventCell.Factory.of(
                        entry,
                        eventTitle(entry),
                        eventDetails(entry),
                        eventIcon(entry),
                        eventColor(entry),
                        index > 0,
                        index + 1 < entries.size()));
            }

            items.add(UItem.asShadow(plugin == null ? null : pluginId));
        }
    }

    private CharSequence eventTitle(PluginRuntimeJournalExport.Entry entry) {
        if (entry.kind == PluginRuntimeJournalCatalog.Kind.RUNTIME_GENERATION_RUNNING) {
            return LocaleController.getString(R.string.FluxGramPluginJournalRuntimeRunning);
        }

        switch (entry.category) {
            case RUNTIME:
                return LocaleController.getString(R.string.FluxGramPluginJournalRuntimeEvent);
            case RESOURCE:
                return LocaleController.getString(R.string.FluxGramPluginJournalResourceEvent);
            case EXTENSION:
                return LocaleController.getString(R.string.FluxGramPluginJournalExtensionEvent);
            case MANAGED_HOOK:
                return LocaleController.getString(R.string.FluxGramPluginJournalHookEvent);
            case MANAGED_TASK:
                return LocaleController.getString(R.string.FluxGramPluginJournalTaskEvent);
            case TYPED_SUBSCRIPTION:
                return LocaleController.getString(R.string.FluxGramPluginJournalSubscriptionEvent);
            case MANAGED_STORAGE:
                return LocaleController.getString(R.string.FluxGramPluginJournalStorageEvent);
            case MANAGED_UI:
                return LocaleController.getString(R.string.FluxGramPluginJournalUiEvent);
            case QUARANTINE:
                return LocaleController.getString(R.string.FluxGramPluginJournalQuarantineEvent);
            case CRASH:
                return LocaleController.getString(R.string.FluxGramPluginJournalCrashEvent);
            case CLEANUP:
                return LocaleController.getString(R.string.FluxGramPluginJournalCleanupEvent);
            default:
                return entry.kind.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private CharSequence eventDetails(PluginRuntimeJournalExport.Entry entry) {
        String detail = LocaleController.formatString(
                R.string.FluxGramPluginJournalEventDetails,
                entry.sequence,
                aliasNumber(entry.process),
                aliasNumber(entry.runtime),
                entry.generation);

        if (entry.kind.valueType == PluginRuntimeJournalCatalog.ValueType.COUNT) {
            detail += LocaleController.formatString(
                    R.string.FluxGramPluginJournalCountValue,
                    entry.value);
        } else if (entry.kind.valueType
                == PluginRuntimeJournalCatalog.ValueType.DURATION_BUCKET) {
            detail += LocaleController.formatString(
                    R.string.FluxGramPluginJournalDurationValue,
                    duration(entry.value));
        }

        return detail;
    }

    private int eventIcon(PluginRuntimeJournalExport.Entry entry) {
        return isFailure(entry) ? R.drawable.msg_warning : R.drawable.msg_log;
    }

    private int eventColor(PluginRuntimeJournalExport.Entry entry) {
        if (isFailure(entry)) {
            return getThemedColor(Theme.key_text_RedRegular);
        }
        if (entry.kind.name().contains("STOPPED")
                || entry.kind.name().contains("CLOSED")
                || entry.kind.name().contains("CANCELLED")) {
            return getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
        }

        return getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private boolean isFailure(PluginRuntimeJournalExport.Entry entry) {
        String name = entry.kind.name();
        return name.contains("FAILED")
                || name.contains("REJECTED")
                || entry.category == PluginRuntimeJournalCatalog.Category.QUARANTINE
                || entry.category == PluginRuntimeJournalCatalog.Category.CRASH;
    }

    private String aliasNumber(String alias) {
        int separator = alias.lastIndexOf('-');
        return separator < 0 ? alias : alias.substring(separator + 1);
    }

    private String duration(int bucket) {
        switch (bucket) {
            case 1:
                return "<1 ms";
            case 2:
                return "<4 ms";
            case 3:
                return "<16 ms";
            case 4:
                return "<100 ms";
            default:
                return ">=100 ms";
        }
    }

    private void addSectionLabel(ArrayList<UItem> items, int textResource) {
        addSectionLabel(items, LocaleController.getString(textResource));
    }

    private void addSectionLabel(ArrayList<UItem> items, CharSequence text) {
        HeaderCell label = new HeaderCell(getParentActivity(), 33, getResourceProvider());
        label.setText(text);
        items.add(UItem.asCustomShadow(label, LayoutHelper.WRAP_CONTENT));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.id == RETRY) {
            loadJournal();
        }
    }
}
