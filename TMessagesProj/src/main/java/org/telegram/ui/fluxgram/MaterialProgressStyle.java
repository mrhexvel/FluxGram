package org.telegram.ui.fluxgram;

import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.Components.CircularProgressGeometry;
import org.telegram.ui.Components.DeterminateCircularProgressGeometry;
import org.telegram.ui.Components.LinearProgressGeometry;
import org.telegram.ui.Components.MiniPlayerGeometry;

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
    private static final LinearProgressGeometry LINEAR_GEOMETRY =
            new LinearProgressGeometry(4, 6, 5, 3, 28, 4, 34, 4, 48);
    private static final DeterminateCircularProgressGeometry PLAYBACK_CIRCULAR_GEOMETRY =
            new DeterminateCircularProgressGeometry(
                     40,
                     3,
                     0,
                     1.3f,
                     10.5f
             );
    private static final MiniPlayerGeometry MINI_PLAYER_GEOMETRY = new MiniPlayerGeometry(
            52,
            12,
            12,
            20,
            36,
            10,
            44,
            48,
            8,
            PLAYBACK_CIRCULAR_GEOMETRY
    );

    private MaterialProgressStyle() {
    }

    public static CircularProgressGeometry circularGeometry(MaterialVisualStyle.Snapshot style) {
        if (!usesMaterialProgress(style)) {
            return null;
        }

        return CIRCULAR_GEOMETRY;
    }

    public static LinearProgressGeometry linearGeometry(MaterialVisualStyle.Snapshot style) {
        return usesMaterialProgress(style) ? LINEAR_GEOMETRY : null;
    }

    public static MiniPlayerGeometry miniPlayerGeometry(MaterialVisualStyle.Snapshot style) {
        return usesMaterialProgress(style) ? MINI_PLAYER_GEOMETRY : null;
    }

    private static boolean usesMaterialProgress(MaterialVisualStyle.Snapshot style) {
        if (style == null || !style.isEnabled(MaterialStylePolicy.Module.PROGRESS)) {
            return false;
        }

        MaterialVisualStyle.Shape shape = style.getShape(
                MaterialStylePolicy.Module.PROGRESS,
                MaterialVisualStyle.ShapeRole.FULL
        );

        return shape != null && shape.isFull();
    }
}
