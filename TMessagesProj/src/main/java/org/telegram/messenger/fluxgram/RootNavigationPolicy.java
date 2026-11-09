package org.telegram.messenger.fluxgram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RootNavigationPolicy {
    public static final String CHATS = "chats";
    public static final String CONTACTS = "contacts";
    public static final String CALLS = "calls";
    public static final String SETTINGS = "settings";
    public static final String PROFILE = "profile";

    public static final int MAX_DESTINATIONS = 4;

    private static final String FORMAT_VERSION = "1";
    private static final int MAX_ENCODED_LENGTH = 96;
    private static final int MAX_NORMALIZATION_INPUTS = 64;
    private static final List<String> DESTINATIONS = Collections.unmodifiableList(Arrays.asList(
            CHATS,
            CONTACTS,
            CALLS,
            SETTINGS,
            PROFILE
    ));
    private static final Set<String> DESTINATION_SET = Collections.unmodifiableSet(
            new HashSet<>(DESTINATIONS)
    );

    private RootNavigationPolicy() {
    }

    public static List<String> knownDestinations() {
        return DESTINATIONS;
    }

    public static State legacyDefault(boolean callsTabVisible) {
        return new State(Arrays.asList(
                CHATS,
                CONTACTS,
                callsTabVisible ? CALLS : SETTINGS,
                PROFILE
        ));
    }

    public static State normalize(Collection<String> requestedOrder) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        int inspected = 0;

        if (requestedOrder != null) {
            for (String destination : requestedOrder) {
                if (inspected++ >= MAX_NORMALIZATION_INPUTS) {
                    break;
                }
                if (DESTINATION_SET.contains(destination)) {
                    unique.add(destination);
                }
            }
        }

        ArrayList<String> order = new ArrayList<>(unique);
        if (!unique.contains(CHATS)) {
            order.add(0, CHATS);
        }

        while (order.size() > MAX_DESTINATIONS) {
            int removeIndex = order.size() - 1;
            if (CHATS.equals(order.get(removeIndex))) {
                removeIndex--;
            }
            order.remove(removeIndex);
        }

        return new State(order);
    }

    public static String encode(State state) {
        State normalized = state == null ? normalize(null) : normalize(state.order);
        StringBuilder encoded = new StringBuilder(FORMAT_VERSION).append(':');

        for (String destination : normalized.order) {
            if (encoded.charAt(encoded.length() - 1) != ':') {
                encoded.append(',');
            }
            encoded.append(destination);
        }

        return encoded.toString();
    }

    public static State decode(String encoded, boolean callsTabVisible) {
        State decoded = decodeExplicit(encoded);

        return decoded == null ? legacyDefault(callsTabVisible) : decoded;
    }

    static State decodeExplicit(String encoded) {
        if (encoded == null || encoded.length() > MAX_ENCODED_LENGTH) {
            return null;
        }

        String[] fields = encoded.split(":", -1);
        if (fields.length != 2 || !FORMAT_VERSION.equals(fields[0]) || fields[1].isEmpty()) {
            return null;
        }

        String[] values = fields[1].split(",", -1);
        if (values.length == 0 || values.length > MAX_DESTINATIONS) {
            return null;
        }

        ArrayList<String> order = new ArrayList<>(values.length);
        HashSet<String> unique = new HashSet<>();

        for (String destination : values) {
            if (!DESTINATION_SET.contains(destination) || !unique.add(destination)) {
                return null;
            }
            order.add(destination);
        }

        if (!unique.contains(CHATS)) {
            return null;
        }

        return new State(order);
    }

    public static final class State {
        private final List<String> order;

        private State(Collection<String> order) {
            this.order = Collections.unmodifiableList(new ArrayList<>(order));
        }

        public List<String> getOrder() {
            return order;
        }

        public int size() {
            return order.size();
        }

        public boolean contains(String destination) {
            return order.contains(destination);
        }

        public int positionOf(String destination) {
            return order.indexOf(destination);
        }

        public String resolveActiveDestination(String destination) {
            return contains(destination) ? destination : CHATS;
        }

        public boolean hasSettingsDestination() {
            return contains(SETTINGS);
        }

        public boolean hasAccountsDestination() {
            return contains(PROFILE);
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

            return order.equals(other.order);
        }

        @Override
        public int hashCode() {
            return Objects.hash(order);
        }
    }
}
