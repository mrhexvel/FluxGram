package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.fluxgram.RootNavigationPolicy;
import org.telegram.messenger.fluxgram.RootNavigationSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.FluxGramRootNavigationCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.fluxgram.FluxGramSettingsSections;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FluxGramRootNavigationSettingsActivity extends BaseFragment {
    private static final int DONE = 1;

    private UniversalRecyclerView listView;
    private ActionBarMenuItem doneItem;
    private ArrayList<String> initialOrder;
    private ArrayList<String> activeOrder;
    private int orderSection;

    @Override
    public View createView(Context context) {
        if (activeOrder == null) {
            RootNavigationPolicy.State state = RootNavigationSettings.getState(
                    getUserConfig().showCallsTab
            );
            initialOrder = new ArrayList<>(state.getOrder());
            activeOrder = new ArrayList<>(state.getOrder());
        }

        actionBar.setTitle(LocaleController.getString(R.string.FluxGramRootNavigation));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == DONE) {
                    confirmSaveAndRestart();
                }
            }
        });
        doneItem = actionBar.createMenu().addItemWithWidth(
                DONE,
                R.drawable.ic_ab_done,
                AndroidUtilities.dp(56),
                LocaleController.getString(R.string.Save)
        );

        FrameLayout content = new FrameLayout(context);
        content.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = content;

        listView = new UniversalRecyclerView(
                this,
                this::fillItems,
                this::onItemClick,
                null
        );
        listView.adapter.setApplyBackground(false);
        listView.listenReorder(this::onReordered);
        listView.setReorderLongPressEnabled(false);
        listView.allowReorder(true);
        FluxGramSettingsSections.apply(listView, currentAccount);
        content.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));

        actionBar.setAdaptiveBackground(listView);
        updateDoneItem();

        return fragmentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        HeaderCell label = new HeaderCell(getParentActivity(), 33, getResourceProvider());
        label.setText(LocaleController.getString(R.string.FluxGramRootNavigation));
        items.add(UItem.asCustomShadow(label, LayoutHelper.WRAP_CONTENT));

        orderSection = adapter.reorderSectionStart();
        for (int index = 0; index < activeOrder.size(); index++) {
            String destination = activeOrder.get(index);
            items.add(FluxGramRootNavigationCell.Factory.of(
                    destination,
                    destinationTitle(destination),
                    true,
                    !RootNavigationPolicy.CHATS.equals(destination),
                    true,
                    index > 0,
                    index + 1 < activeOrder.size(),
                    direction -> moveItem(destination, direction)
            ));
        }
        adapter.reorderSectionEnd();

        for (String destination : RootNavigationPolicy.knownDestinations()) {
            if (!activeOrder.contains(destination)) {
                items.add(FluxGramRootNavigationCell.Factory.of(
                        destination,
                        destinationTitle(destination),
                        false,
                        true,
                        false,
                        false,
                        false,
                        null
                ));
            }
        }

        items.add(UItem.asShadow(LocaleController.getString(R.string.FluxGramRootNavigationInfo)));
    }

    private void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.instanceOf(FluxGramRootNavigationCell.Factory.class)) {
            return;
        }

        String destination = (String) item.object;
        if (RootNavigationPolicy.CHATS.equals(destination)) {
            showRequiredDestination();
            return;
        }
        if (activeOrder.contains(destination)) {
            activeOrder.remove(destination);
        } else if (activeOrder.size() >= RootNavigationPolicy.MAX_DESTINATIONS) {
            showLimitReached();
            return;
        } else {
            activeOrder.add(destination);
        }

        listView.adapter.update(true);
        updateDoneItem();
    }

    private void onReordered(int sectionId, ArrayList<UItem> items) {
        if (sectionId != orderSection) {
            return;
        }

        Set<String> active = new HashSet<>(activeOrder);
        ArrayList<String> reordered = new ArrayList<>();
        for (UItem item : items) {
            if (item.object instanceof String && active.contains(item.object)) {
                reordered.add((String) item.object);
            }
        }
        if (reordered.size() == activeOrder.size()
                && reordered.contains(RootNavigationPolicy.CHATS)) {
            activeOrder = reordered;
            updateDoneItem();
        }
    }

    private void moveItem(String destination, int direction) {
        int index = activeOrder.indexOf(destination);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= activeOrder.size()) {
            return;
        }

        activeOrder.remove(index);
        activeOrder.add(target, destination);
        listView.adapter.update(true);
        updateDoneItem();
    }

    private void updateDoneItem() {
        if (doneItem != null) {
            doneItem.setVisibility(activeOrder.equals(initialOrder) ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void showLimitReached() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.FluxGramRootNavigation))
                .setMessage(LocaleController.getString(R.string.FluxGramRootNavigationLimit))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void showRequiredDestination() {
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.FluxGramRootNavigation))
                .setMessage(LocaleController.getString(R.string.FluxGramRootNavigationRequired))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void confirmSaveAndRestart() {
        if (activeOrder.equals(initialOrder)) {
            return;
        }

        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.FluxGramTabletModeRestartTitle))
                .setMessage(LocaleController.getString(
                        R.string.FluxGramRootNavigationRestartMessage))
                .setPositiveButton(LocaleController.getString(R.string.FluxGramRestartNow),
                        (dialog, which) -> restartWithOrder(new ArrayList<>(activeOrder)))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
    }

    private void restartWithOrder(List<String> order) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }

        Intent intent = activity.getPackageManager().getLaunchIntentForPackage(
                activity.getPackageName());
        if (intent == null || !RootNavigationSettings.setOrder(order)) {
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(LocaleController.getString(R.string.FluxGramRootNavigation))
                    .setMessage(LocaleController.getString(
                            R.string.FluxGramRootNavigationRestartError))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .create());
            return;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.finishAffinity();
        activity.startActivity(intent);
        System.exit(0);
    }

    private CharSequence destinationTitle(String destination) {
        if (RootNavigationPolicy.CHATS.equals(destination)) {
            return LocaleController.getString(R.string.MainTabsChats);
        }
        if (RootNavigationPolicy.CONTACTS.equals(destination)) {
            return LocaleController.getString(R.string.MainTabsContacts);
        }
        if (RootNavigationPolicy.CALLS.equals(destination)) {
            return LocaleController.getString(R.string.MainTabsCalls);
        }
        if (RootNavigationPolicy.SETTINGS.equals(destination)) {
            return LocaleController.getString(R.string.Settings);
        }

        return LocaleController.getString(R.string.MainTabsProfile);
    }
}
