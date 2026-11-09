package org.telegram.messenger.fluxgram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RootNavigationSnapshot {
    public static final String CHATS = RootNavigationPolicy.CHATS;
    public static final String CONTACTS = RootNavigationPolicy.CONTACTS;
    public static final String CALLS = RootNavigationPolicy.CALLS;
    public static final String SETTINGS = RootNavigationPolicy.SETTINGS;
    public static final String PROFILE = RootNavigationPolicy.PROFILE;

    private final int account;
    private final int rootIdentity;
    private final boolean bottomNavigationVisible;
    private final List<String> effectiveOrder;
    private final String activeDestination;

    private RootNavigationSnapshot(
            int account,
            int rootIdentity,
            boolean bottomNavigationVisible,
            List<String> effectiveOrder,
            String activeDestination
    ) {
        this.account = account;
        this.rootIdentity = rootIdentity;
        this.bottomNavigationVisible = bottomNavigationVisible;
        this.effectiveOrder = Collections.unmodifiableList(new ArrayList<>(effectiveOrder));
        this.activeDestination = this.effectiveOrder.contains(activeDestination)
                ? activeDestination
                : CHATS;
    }

    public static RootNavigationSnapshot create(
            int account,
            int rootIdentity,
            int bottomNavigationMode,
            RootNavigationPolicy.State state,
            String activeDestination
    ) {
        boolean bottomNavigationVisible = BottomNavigationSettings.isVisible(bottomNavigationMode);
        RootNavigationPolicy.State effectiveState = state == null
                ? RootNavigationPolicy.normalize(null)
                : state;
        List<String> effectiveOrder = bottomNavigationVisible
                ? effectiveState.getOrder()
                : Collections.singletonList(CHATS);

        return new RootNavigationSnapshot(
                account,
                rootIdentity,
                bottomNavigationVisible,
                effectiveOrder,
                activeDestination
        );
    }

    public int getAccount() {
        return account;
    }

    public int getRootIdentity() {
        return rootIdentity;
    }

    public boolean isBottomNavigationVisible() {
        return bottomNavigationVisible;
    }

    public List<String> getEffectiveOrder() {
        return effectiveOrder;
    }

    public String getActiveDestination() {
        return activeDestination;
    }

    public boolean hasDestination(String destination) {
        return effectiveOrder.contains(destination);
    }

    public static boolean isKnownDestination(String destination) {
        return RootNavigationPolicy.knownDestinations().contains(destination);
    }

    public boolean canSelectInRoot(String destination) {
        return effectiveOrder.contains(destination);
    }

    public boolean matches(RootNavigationSnapshot other) {
        return equals(other);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof RootNavigationSnapshot)) {
            return false;
        }

        RootNavigationSnapshot other = (RootNavigationSnapshot) object;

        return account == other.account
                && rootIdentity == other.rootIdentity
                && bottomNavigationVisible == other.bottomNavigationVisible
                && effectiveOrder.equals(other.effectiveOrder)
                && activeDestination.equals(other.activeDestination);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                account,
                rootIdentity,
                bottomNavigationVisible,
                effectiveOrder,
                activeDestination
        );
    }
}
