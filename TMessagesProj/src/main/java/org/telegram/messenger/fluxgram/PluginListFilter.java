package org.telegram.messenger.fluxgram;

public final class PluginListFilter {
    public enum Filter {
        ALL,
        ENABLED,
        DISABLED,
        ATTENTION
    }

    private PluginListFilter() {
    }

    public static Filter normalize(Filter filter, int externalPluginCount) {
        if (filter == null || externalPluginCount < 2) {
            return Filter.ALL;
        }

        return filter;
    }

    public static boolean matches(Filter filter, boolean enabled, boolean attention) {
        Filter effective = filter == null ? Filter.ALL : filter;

        if (effective == Filter.ENABLED) {
            return enabled;
        }
        if (effective == Filter.DISABLED) {
            return !enabled;
        }
        if (effective == Filter.ATTENTION) {
            return attention;
        }

        return true;
    }
}
