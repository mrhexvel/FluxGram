package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.FilterChipGeometry;

public final class MaterialListStyle {
    private static final FilterChipGeometry FILTER_CHIP_GEOMETRY =
            new FilterChipGeometry(32, 8, 16, 8, 0.08f, 0.22f);

    private MaterialListStyle() {
    }

    public static FilterChipGeometry filterChipGeometry(MaterialVisualStyle.Snapshot style) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.LISTS)) {
            return null;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.LISTS,
                MaterialVisualStyle.ShapeRole.SMALL
        );
        Integer spacing = style.getSpacing(
                MaterialStylePolicy.Module.LISTS,
                MaterialVisualStyle.SpacingRole.SMALL
        );

        return shape != null
                && !shape.isFull()
                && shape.getRadiusDp() == FILTER_CHIP_GEOMETRY.getUnselectedCornerRadiusDp()
                && spacing != null
                && spacing == FILTER_CHIP_GEOMETRY.getGapDp()
                ? FILTER_CHIP_GEOMETRY
                : null;
    }
}
