package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.SwitchGeometry;

public final class MaterialControlsStyle {
    private static final SwitchGeometry SWITCH_GEOMETRY = new SwitchGeometry(
            44,
            24,
            14,
            20,
            24,
            18,
            2,
            16,
            16
    );

    private MaterialControlsStyle() {
    }

    public static SwitchGeometry switchGeometry(MaterialVisualStyle.Snapshot style) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.CONTROLS)) {
            return null;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.CONTROLS,
                MaterialVisualStyle.ShapeRole.FULL
        );

        return shape != null && shape.isFull() ? SWITCH_GEOMETRY : null;
    }
}
