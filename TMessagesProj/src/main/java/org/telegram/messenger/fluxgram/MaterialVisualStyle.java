package org.telegram.messenger.fluxgram;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class MaterialVisualStyle {
    private static final Map<TypographyRole, Typography> TYPOGRAPHY = typography();
    private static final Map<ShapeRole, Shape> SHAPES = shapes();
    private static final Map<SpacingRole, Integer> SPACING = spacing();
    private static final Map<ElevationRole, Integer> ELEVATION = elevation();
    private static final Map<StateRole, State> STATES = states();
    private static final Map<MotionRole, Motion> MOTION = motion();

    private MaterialVisualStyle() {
    }

    public static Snapshot telegram() {
        return new Snapshot(Collections.emptySet());
    }

    public static Snapshot resolve(MaterialStylePolicy.EffectiveSnapshot effectiveSnapshot) {
        if (effectiveSnapshot == null) {
            return telegram();
        }

        return new Snapshot(effectiveSnapshot.getEffectiveModules());
    }

    private static Map<TypographyRole, Typography> typography() {
        EnumMap<TypographyRole, Typography> values = new EnumMap<>(TypographyRole.class);
        values.put(TypographyRole.TITLE_LARGE, new Typography(22, 28, 400));
        values.put(TypographyRole.TITLE_MEDIUM, new Typography(16, 24, 500));
        values.put(TypographyRole.BODY_LARGE, new Typography(16, 24, 400));
        values.put(TypographyRole.BODY_MEDIUM, new Typography(14, 20, 400));
        values.put(TypographyRole.LABEL_LARGE, new Typography(14, 20, 500));
        values.put(TypographyRole.LABEL_MEDIUM, new Typography(12, 16, 500));
        return Collections.unmodifiableMap(values);
    }

    private static Map<ShapeRole, Shape> shapes() {
        EnumMap<ShapeRole, Shape> values = new EnumMap<>(ShapeRole.class);
        values.put(ShapeRole.NONE, new Shape(0, false));
        values.put(ShapeRole.EXTRA_SMALL, new Shape(4, false));
        values.put(ShapeRole.SMALL, new Shape(8, false));
        values.put(ShapeRole.MEDIUM, new Shape(12, false));
        values.put(ShapeRole.LARGE, new Shape(16, false));
        values.put(ShapeRole.EXTRA_LARGE, new Shape(28, false));
        values.put(ShapeRole.FULL, new Shape(0, true));
        return Collections.unmodifiableMap(values);
    }

    private static Map<SpacingRole, Integer> spacing() {
        EnumMap<SpacingRole, Integer> values = new EnumMap<>(SpacingRole.class);
        values.put(SpacingRole.EXTRA_SMALL, 4);
        values.put(SpacingRole.SMALL, 8);
        values.put(SpacingRole.MEDIUM, 12);
        values.put(SpacingRole.LARGE, 16);
        values.put(SpacingRole.EXTRA_LARGE, 24);
        values.put(SpacingRole.HUGE, 32);
        return Collections.unmodifiableMap(values);
    }

    private static Map<ElevationRole, Integer> elevation() {
        EnumMap<ElevationRole, Integer> values = new EnumMap<>(ElevationRole.class);
        values.put(ElevationRole.LEVEL_0, 0);
        values.put(ElevationRole.LEVEL_1, 1);
        values.put(ElevationRole.LEVEL_2, 3);
        values.put(ElevationRole.LEVEL_3, 6);
        values.put(ElevationRole.LEVEL_4, 8);
        values.put(ElevationRole.LEVEL_5, 12);
        return Collections.unmodifiableMap(values);
    }

    private static Map<StateRole, State> states() {
        EnumMap<StateRole, State> values = new EnumMap<>(StateRole.class);
        values.put(StateRole.HOVER, new State(StateTreatment.OVERLAY, 0.08f));
        values.put(StateRole.FOCUS, new State(StateTreatment.OVERLAY, 0.10f));
        values.put(StateRole.PRESSED, new State(StateTreatment.OVERLAY, 0.10f));
        values.put(StateRole.DRAGGED, new State(StateTreatment.OVERLAY, 0.16f));
        values.put(StateRole.DISABLED_CONTENT, new State(StateTreatment.CONTENT_ALPHA, 0.38f));
        values.put(StateRole.DISABLED_CONTAINER, new State(StateTreatment.CONTAINER_ALPHA, 0.12f));
        values.put(StateRole.SELECTED, new State(StateTreatment.SEMANTIC, 0.0f));
        return Collections.unmodifiableMap(values);
    }

    private static Map<MotionRole, Motion> motion() {
        EnumMap<MotionRole, Motion> values = new EnumMap<>(MotionRole.class);
        values.put(MotionRole.SHORT_STATE, new Motion(100, 0.2f, 0.0f, 0.0f, 1.0f));
        values.put(MotionRole.STANDARD_ENTER, new Motion(300, 0.0f, 0.0f, 0.0f, 1.0f));
        values.put(MotionRole.STANDARD_EXIT, new Motion(200, 0.3f, 0.0f, 1.0f, 1.0f));
        values.put(MotionRole.EMPHASIZED_ENTER, new Motion(500, 0.05f, 0.7f, 0.1f, 1.0f));
        values.put(MotionRole.EMPHASIZED_EXIT, new Motion(200, 0.3f, 0.0f, 0.8f, 0.15f));
        return Collections.unmodifiableMap(values);
    }

    public enum TypographyRole {
        TITLE_LARGE,
        TITLE_MEDIUM,
        BODY_LARGE,
        BODY_MEDIUM,
        LABEL_LARGE,
        LABEL_MEDIUM
    }

    public enum ShapeRole {
        NONE,
        EXTRA_SMALL,
        SMALL,
        MEDIUM,
        LARGE,
        EXTRA_LARGE,
        FULL
    }

    public enum SpacingRole {
        EXTRA_SMALL,
        SMALL,
        MEDIUM,
        LARGE,
        EXTRA_LARGE,
        HUGE
    }

    public enum ElevationRole {
        LEVEL_0,
        LEVEL_1,
        LEVEL_2,
        LEVEL_3,
        LEVEL_4,
        LEVEL_5
    }

    public enum StateRole {
        HOVER,
        FOCUS,
        PRESSED,
        DRAGGED,
        DISABLED_CONTENT,
        DISABLED_CONTAINER,
        SELECTED
    }

    public enum StateTreatment {
        OVERLAY,
        CONTENT_ALPHA,
        CONTAINER_ALPHA,
        SEMANTIC
    }

    public enum MotionRole {
        SHORT_STATE,
        STANDARD_ENTER,
        STANDARD_EXIT,
        EMPHASIZED_ENTER,
        EMPHASIZED_EXIT
    }

    public static final class Snapshot {
        private final Set<MaterialStylePolicy.Module> enabledModules;

        private Snapshot(Collection<MaterialStylePolicy.Module> enabledModules) {
            EnumSet<MaterialStylePolicy.Module> copy = enabledModules.isEmpty()
                    ? EnumSet.noneOf(MaterialStylePolicy.Module.class)
                    : EnumSet.copyOf(enabledModules);
            this.enabledModules = Collections.unmodifiableSet(copy);
        }

        public Set<MaterialStylePolicy.Module> getEnabledModules() {
            return enabledModules;
        }

        public boolean isEnabled(MaterialStylePolicy.Module module) {
            return module != null && enabledModules.contains(module);
        }

        public Typography getTypography(
                MaterialStylePolicy.Module module,
                TypographyRole role
        ) {
            return isEnabled(module) && role != null ? TYPOGRAPHY.get(role) : null;
        }

        public Shape getShape(MaterialStylePolicy.Module module, ShapeRole role) {
            return isEnabled(module) && role != null ? SHAPES.get(role) : null;
        }

        public Integer getSpacing(MaterialStylePolicy.Module module, SpacingRole role) {
            return isEnabled(module) && role != null ? SPACING.get(role) : null;
        }

        public Integer getElevation(MaterialStylePolicy.Module module, ElevationRole role) {
            return isEnabled(module) && role != null ? ELEVATION.get(role) : null;
        }

        public State getState(MaterialStylePolicy.Module module, StateRole role) {
            return isEnabled(module) && role != null ? STATES.get(role) : null;
        }

        public Motion getMotion(MaterialStylePolicy.Module module, MotionRole role) {
            return isEnabled(module) && role != null ? MOTION.get(role) : null;
        }
    }

    public static final class Typography {
        private final int sizeSp;
        private final int lineHeightSp;
        private final int weight;

        private Typography(int sizeSp, int lineHeightSp, int weight) {
            this.sizeSp = sizeSp;
            this.lineHeightSp = lineHeightSp;
            this.weight = weight;
        }

        public int getSizeSp() {
            return sizeSp;
        }

        public int getLineHeightSp() {
            return lineHeightSp;
        }

        public int getWeight() {
            return weight;
        }
    }

    public static final class Shape {
        private final int radiusDp;
        private final boolean full;

        private Shape(int radiusDp, boolean full) {
            this.radiusDp = radiusDp;
            this.full = full;
        }

        public int getRadiusDp() {
            return radiusDp;
        }

        public boolean isFull() {
            return full;
        }
    }

    public static final class State {
        private final StateTreatment treatment;
        private final float amount;

        private State(StateTreatment treatment, float amount) {
            this.treatment = treatment;
            this.amount = amount;
        }

        public StateTreatment getTreatment() {
            return treatment;
        }

        public float getAmount() {
            return amount;
        }
    }

    public static final class Motion {
        private final int durationMs;
        private final float controlX1;
        private final float controlY1;
        private final float controlX2;
        private final float controlY2;

        private Motion(
                int durationMs,
                float controlX1,
                float controlY1,
                float controlX2,
                float controlY2
        ) {
            this.durationMs = durationMs;
            this.controlX1 = controlX1;
            this.controlY1 = controlY1;
            this.controlX2 = controlX2;
            this.controlY2 = controlY2;
        }

        public int getDurationMs() {
            return durationMs;
        }

        public float getControlX1() {
            return controlX1;
        }

        public float getControlY1() {
            return controlY1;
        }

        public float getControlX2() {
            return controlX2;
        }

        public float getControlY2() {
            return controlY2;
        }
    }
}
