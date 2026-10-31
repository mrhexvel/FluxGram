package org.telegram.messenger.plugins.signing;

import java.io.IOException;
import java.util.Arrays;

public final class PluginSignerReview {
    public final PluginSignerTransition.Result transition;
    public final PluginSignerState before;
    public final PluginSignerState after;
    public final PluginKeyRotationProof rotationProof;

    private final PluginSignerJournal journal;

    private PluginSignerReview(
            PluginSignerTransition.Result transition,
            PluginSignerState before,
            PluginSignerState after,
            PluginKeyRotationProof rotationProof,
            PluginSignerJournal journal) {
        this.transition = transition;
        this.before = before;
        this.after = after;
        this.rotationProof = rotationProof;
        this.journal = journal;
    }

    public static PluginSignerReview prepare(
            String pluginId,
            String archiveSha256,
            PluginSignerState before,
            PluginSignatureVerification candidate,
            PluginKeyRotationProof rotationProof) {
        PluginSignerIdentity pinned = before == null ? null : before.identity;
        PluginSignerTransition.Result transition = PluginSignerTransition.evaluate(
                pluginId,
                archiveSha256,
                pinned,
                candidate,
                rotationProof);

        if (transition.decision == PluginSignerTransition.Decision.BLOCK) {
            return new PluginSignerReview(transition, before, null, rotationProof, null);
        }

        PluginSignerState after = new PluginSignerState(
                archiveSha256,
                transition.nextIdentity);
        PluginKeyRotationProof appliedRotation =
                transition.reason == PluginSignerTransition.Reason.VERIFIED_ROTATION
                        ? rotationProof
                        : null;
        PluginSignerJournal journal = new PluginSignerJournal(
                pluginId,
                before,
                after,
                transition.reason,
                appliedRotation);

        return new PluginSignerReview(transition, before, after, rotationProof, journal);
    }

    public PluginSignerJournal approve(boolean confirmed) throws IOException {
        if (transition.decision == PluginSignerTransition.Decision.BLOCK) {
            throw new IOException("Plugin signer transition is blocked: " + transition.reason.name());
        }
        if (transition.decision == PluginSignerTransition.Decision.CONFIRM && !confirmed) {
            throw new IOException("Plugin signer transition requires confirmation");
        }

        return journal;
    }

    public boolean matches(PluginSignerJournal approved) throws IOException {
        return journal != null
                && approved != null
                && Arrays.equals(journal.encode(), approved.encode());
    }
}
