package org.telegram.ui.Components;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;

public final class FluxGramRootNavigationCell extends TextCheckCell {
    private static final int ACTION_MOVE_UP = 0x01020001;
    private static final int ACTION_MOVE_DOWN = 0x01020002;

    private final View reorderHandle;
    private boolean toggleEnabled;
    private boolean canMoveUp;
    private boolean canMoveDown;
    private Utilities.Callback<Integer> moveCallback;
    private Runnable reorderCallback;

    public FluxGramRootNavigationCell(
            Context context,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context, resourcesProvider);

        setIcon(R.drawable.list_reorder);
        reorderHandle = new View(context);
        reorderHandle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        reorderHandle.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && reorderCallback != null) {
                reorderCallback.run();
            }

            return true;
        });
        addView(reorderHandle, LayoutHelper.createFrame(
                56,
                LayoutHelper.MATCH_PARENT,
                (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP
        ));
    }

    public void setItem(
            CharSequence title,
            boolean checked,
            boolean toggleEnabled,
            boolean reorderEnabled,
            boolean needDivider,
            boolean canMoveUp,
            boolean canMoveDown,
            Runnable reorderCallback,
            Utilities.Callback<Integer> moveCallback
    ) {
        this.toggleEnabled = toggleEnabled;
        this.canMoveUp = canMoveUp;
        this.canMoveDown = canMoveDown;
        this.reorderCallback = reorderCallback;
        this.moveCallback = moveCallback;

        setTextAndCheck(title, checked, needDivider);
        setIcon(reorderEnabled ? R.drawable.list_reorder : 0);
        getCheckBox().setEnabled(toggleEnabled);
        reorderHandle.setVisibility(reorderEnabled ? VISIBLE : INVISIBLE);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);

        if (!toggleEnabled) {
            info.setCheckable(false);
        }
        if (canMoveUp) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_MOVE_UP,
                    LocaleController.getString(R.string.FluxGramMoveUp)
            ));
        }
        if (canMoveDown) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    ACTION_MOVE_DOWN,
                    LocaleController.getString(R.string.FluxGramMoveDown)
            ));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == ACTION_MOVE_UP && canMoveUp && moveCallback != null) {
            moveCallback.run(-1);
            return true;
        }
        if (action == ACTION_MOVE_DOWN && canMoveDown && moveCallback != null) {
            moveCallback.run(1);
            return true;
        }

        return super.performAccessibilityAction(action, arguments);
    }

    public static final class Factory extends UItem.UItemFactory<FluxGramRootNavigationCell> {
        private static final int FLAG_TOGGLE_ENABLED = 1;
        private static final int FLAG_REORDER_ENABLED = 1 << 1;
        private static final int FLAG_MOVE_UP = 1 << 2;
        private static final int FLAG_MOVE_DOWN = 1 << 3;

        static {
            setup(new Factory());
        }

        @Override
        public FluxGramRootNavigationCell createView(
                Context context,
                RecyclerListView listView,
                int currentAccount,
                int classGuid,
                Theme.ResourcesProvider resourcesProvider
        ) {
            return new FluxGramRootNavigationCell(context, resourcesProvider);
        }

        @Override
        public void bindView(
                View view,
                UItem item,
                boolean divider,
                UniversalAdapter adapter,
                UniversalRecyclerView listView
        ) {
            FluxGramRootNavigationCell cell = (FluxGramRootNavigationCell) view;
            boolean reorderEnabled = (item.flags & FLAG_REORDER_ENABLED) != 0;
            cell.setItem(
                    item.text,
                    item.checked,
                    (item.flags & FLAG_TOGGLE_ENABLED) != 0,
                    reorderEnabled,
                    divider,
                    (item.flags & FLAG_MOVE_UP) != 0,
                    (item.flags & FLAG_MOVE_DOWN) != 0,
                    reorderEnabled ? () -> listView.startReorder(cell) : null,
                    item.intCallback
            );
        }

        @Override
        public boolean equals(UItem first, UItem second) {
            return first.object != null && first.object.equals(second.object);
        }

        @Override
        public boolean contentsEquals(UItem first, UItem second) {
            return equals(first, second)
                    && first.checked == second.checked
                    && first.flags == second.flags
                    && first.text != null
                    && first.text.equals(second.text);
        }

        public static UItem of(
                String semanticId,
                CharSequence title,
                boolean checked,
                boolean toggleEnabled,
                boolean reorderEnabled,
                boolean canMoveUp,
                boolean canMoveDown,
                Utilities.Callback<Integer> moveCallback
        ) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = semanticId;
            item.text = title;
            item.checked = checked;
            item.flags = (toggleEnabled ? FLAG_TOGGLE_ENABLED : 0)
                    | (reorderEnabled ? FLAG_REORDER_ENABLED : 0)
                    | (canMoveUp ? FLAG_MOVE_UP : 0)
                    | (canMoveDown ? FLAG_MOVE_DOWN : 0);
            item.intCallback = moveCallback;

            return item;
        }
    }
}
