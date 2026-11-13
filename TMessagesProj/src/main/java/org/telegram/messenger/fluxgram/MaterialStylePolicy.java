package org.telegram.messenger.fluxgram;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class MaterialStylePolicy {
    private static final String FORMAT_VERSION = "2";
    private static final String LEGACY_FORMAT_VERSION = "1";
    private static final int MAX_ENCODED_LENGTH = 64;
    private static final long LEGACY_DYNAMIC_COLORS_MASK = 1L;
    private static final long KNOWN_MODULE_MASK = LEGACY_DYNAMIC_COLORS_MASK | moduleMask(
            EnumSet.allOf(Module.class)
    );

    private MaterialStylePolicy() {
    }

    public static State defaults() {
        return new State(Preset.TELEGRAM, Collections.emptySet());
    }

    public static State create(Preset preset, Collection<Module> customModules) {
        return new State(normalizePreset(preset), normalizeModules(customModules));
    }

    public static State selectPreset(State state, Preset preset) {
        State normalized = normalizeState(state);

        return new State(normalizePreset(preset), normalized.customModules);
    }

    public static State setModuleEnabled(State state, Module module, boolean enabled) {
        State normalized = normalizeState(state);

        if (module == null) {
            return normalized;
        }

        EnumSet<Module> modules = normalized.preset == Preset.ADVANCED
                ? copyModules(normalized.customModules)
                : modulesForPreset(normalized.preset);

        if (enabled) {
            modules.add(module);
        } else {
            modules.remove(module);
        }

        return new State(Preset.ADVANCED, modules);
    }

    public static EffectiveSnapshot resolve(State state) {
        State normalized = normalizeState(state);
        EnumSet<Module> requested = normalized.preset == Preset.ADVANCED
                ? copyModules(normalized.customModules)
                : modulesForPreset(normalized.preset);

        return new EffectiveSnapshot(normalized.preset, requested);
    }

    public static String encode(State state) {
        State normalized = normalizeState(state);
        long moduleMask = moduleMask(normalized.customModules);

        return FORMAT_VERSION
                + ":"
                + normalized.preset.id
                + ":"
                + Long.toHexString(moduleMask);
    }

    public static State decode(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
            return defaults();
        }

        String[] fields = encoded.split(":", -1);

        if (fields.length != 3
                || (!FORMAT_VERSION.equals(fields[0])
                && !LEGACY_FORMAT_VERSION.equals(fields[0]))) {
            return defaults();
        }

        Preset preset = parsePreset(fields[1]);
        Long moduleMask = parseModuleMask(fields[2]);

        long knownModuleMask = FORMAT_VERSION.equals(fields[0])
                ? KNOWN_MODULE_MASK & ~LEGACY_DYNAMIC_COLORS_MASK
                : KNOWN_MODULE_MASK & ~(1L << Module.PLAYER.bit);

        if (preset == null || moduleMask == null || (moduleMask & ~knownModuleMask) != 0) {
            return defaults();
        }

        EnumSet<Module> modules = modulesFromMask(moduleMask);

        if (LEGACY_FORMAT_VERSION.equals(fields[0]) && modules.contains(Module.PROGRESS)) {
            modules.add(Module.PLAYER);
        }

        return new State(preset, modules);
    }

    private static State normalizeState(State state) {
        if (state == null) {
            return defaults();
        }

        return new State(normalizePreset(state.preset), state.customModules);
    }

    private static Preset normalizePreset(Preset preset) {
        return preset == null ? Preset.TELEGRAM : preset;
    }

    private static EnumSet<Module> normalizeModules(Collection<Module> modules) {
        EnumSet<Module> normalized = EnumSet.noneOf(Module.class);

        if (modules != null) {
            for (Module module : modules) {
                if (module != null) {
                    normalized.add(module);
                }
            }
        }

        return normalized;
    }

    private static EnumSet<Module> modulesForPreset(Preset preset) {
        if (preset == Preset.ICONS) {
            return EnumSet.of(Module.MATERIAL_SYMBOLS);
        }
        if (preset == Preset.BALANCED) {
            return EnumSet.of(
                    Module.MATERIAL_SYMBOLS,
                    Module.NAVIGATION,
                    Module.CONTROLS,
                    Module.SURFACES,
                    Module.LISTS,
                    Module.PROGRESS,
                    Module.PLAYER
            );
        }

        return EnumSet.noneOf(Module.class);
    }

    private static EnumSet<Module> copyModules(Collection<Module> modules) {
        return modules.isEmpty()
                ? EnumSet.noneOf(Module.class)
                : EnumSet.copyOf(modules);
    }

    private static long moduleMask(Collection<Module> modules) {
        long mask = 0;

        for (Module module : modules) {
            mask |= 1L << module.bit;
        }

        return mask;
    }

    private static EnumSet<Module> modulesFromMask(long mask) {
        EnumSet<Module> modules = EnumSet.noneOf(Module.class);

        for (Module module : Module.values()) {
            if ((mask & 1L << module.bit) != 0) {
                modules.add(module);
            }
        }

        return modules;
    }

    private static Preset parsePreset(String value) {
        if (value.length() != 1 || value.charAt(0) < '0' || value.charAt(0) > '9') {
            return null;
        }

        int id = value.charAt(0) - '0';

        for (Preset preset : Preset.values()) {
            if (preset.id == id) {
                return preset;
            }
        }

        return null;
    }

    private static Long parseModuleMask(String value) {
        if (value.isEmpty() || value.length() > 16) {
            return null;
        }

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean digit = character >= '0' && character <= '9';
            boolean lower = character >= 'a' && character <= 'f';

            if (!digit && !lower) {
                return null;
            }
        }

        try {
            return Long.parseLong(value, 16);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public enum Preset {
        TELEGRAM(0),
        ICONS(1),
        BALANCED(2),
        ADVANCED(3);

        private final int id;

        Preset(int id) {
            this.id = id;
        }
    }

    public enum Module {
        MATERIAL_SYMBOLS(1),
        NAVIGATION(2),
        CONTROLS(3),
        SURFACES(4),
        LISTS(5),
        PROGRESS(6),
        PLAYER(11),
        MOTION(7),
        CHAT_CHROME(8),
        MESSAGE_BUBBLES(9),
        PROFILE(10);

        private final int bit;

        Module(int bit) {
            this.bit = bit;
        }
    }

    public static final class State {
        private final Preset preset;
        private final Set<Module> customModules;

        private State(Preset preset, Collection<Module> customModules) {
            this.preset = preset;
            this.customModules = immutableModules(customModules);
        }

        public Preset getPreset() {
            return preset;
        }

        public Set<Module> getCustomModules() {
            return customModules;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof State)) {
                return false;
            }

            State other = (State) object;

            return preset == other.preset && customModules.equals(other.customModules);
        }

        @Override
        public int hashCode() {
            return Objects.hash(preset, customModules);
        }
    }

    public static final class EffectiveSnapshot {
        private final Preset preset;
        private final Set<Module> requestedModules;
        private final Set<Module> effectiveModules;

        private EffectiveSnapshot(
                Preset preset,
                Collection<Module> requestedModules
        ) {
            this.preset = preset;
            this.requestedModules = immutableModules(requestedModules);
            this.effectiveModules = immutableModules(requestedModules);
        }

        public Preset getPreset() {
            return preset;
        }

        public Set<Module> getRequestedModules() {
            return requestedModules;
        }

        public Set<Module> getEffectiveModules() {
            return effectiveModules;
        }

        public boolean isRequested(Module module) {
            return requestedModules.contains(module);
        }

        public boolean isEnabled(Module module) {
            return effectiveModules.contains(module);
        }
    }

    private static Set<Module> immutableModules(Collection<Module> modules) {
        return Collections.unmodifiableSet(copyModules(modules));
    }
}
