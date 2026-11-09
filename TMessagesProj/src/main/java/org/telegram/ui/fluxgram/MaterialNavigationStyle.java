package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.ActiveIndicatorGeometry;

public final class MaterialNavigationStyle {
    private static final ActiveIndicatorGeometry ACTIVE_INDICATOR_GEOMETRY =
            new ActiveIndicatorGeometry(56, 32, 16, 4);

    private MaterialNavigationStyle() {
    }

    public static ActiveIndicatorGeometry activeIndicatorGeometry(
            MaterialVisualStyle.Snapshot style
    ) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.NAVIGATION)) {
            return null;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.NAVIGATION,
                MaterialVisualStyle.ShapeRole.FULL
        );

        return shape != null && shape.isFull() ? ACTIVE_INDICATOR_GEOMETRY : null;
    }

    public static boolean usesDockedContainer(MaterialVisualStyle.Snapshot style) {
        return activeIndicatorGeometry(style) != null;
    }
}
