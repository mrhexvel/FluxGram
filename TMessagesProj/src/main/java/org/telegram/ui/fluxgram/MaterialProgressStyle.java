package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.CircularProgressGeometry;

public final class MaterialProgressStyle {
    private static final CircularProgressGeometry CIRCULAR_GEOMETRY =
            new CircularProgressGeometry(
                    48,
                    4,
                    4,
                    1.6f,
                    15,
                    6000
            );

    private MaterialProgressStyle() {
    }

    public static CircularProgressGeometry circularGeometry(MaterialVisualStyle.Snapshot style) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.PROGRESS)) {
            return null;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.PROGRESS,
                MaterialVisualStyle.ShapeRole.FULL
        );

        return shape != null && shape.isFull() ? CIRCULAR_GEOMETRY : null;
    }
}
