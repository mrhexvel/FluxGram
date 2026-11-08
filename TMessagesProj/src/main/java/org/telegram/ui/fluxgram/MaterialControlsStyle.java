package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.CheckBoxGeometry;
import org.telegram.ui.Components.SwitchGeometry;

public final class MaterialControlsStyle {
    private static final CheckBoxGeometry CHECK_BOX_GEOMETRY = new CheckBoxGeometry(
            18,
            2,
            2,
            0.6f,
            350,
            150,
            50
    );
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

    public static CheckBoxGeometry checkBoxGeometry(MaterialVisualStyle.Snapshot style) {
        if (!usesMaterialControls(style)) {
            return null;
        }

        return CHECK_BOX_GEOMETRY;
    }

    public static SwitchGeometry switchGeometry(MaterialVisualStyle.Snapshot style) {
        if (!usesMaterialControls(style)) {
            return null;
        }

        return SWITCH_GEOMETRY;
    }

    private static boolean usesMaterialControls(MaterialVisualStyle.Snapshot style) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.CONTROLS)) {
            return false;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.CONTROLS,
                MaterialVisualStyle.ShapeRole.FULL
        );

        return shape != null && shape.isFull();
    }
}
