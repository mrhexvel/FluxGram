package org.telegram.messenger.plugins.reputation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PluginReputationPolicy {
    public enum Decision {
        NO_ASSERTION,
        BLOCK
    }

    public static final String BLOCK_ARCHIVE = "block.reputation.archive_digest";
    public static final String BLOCK_KEY = "block.reputation.developer_key";
    public static final String RECORD_STALE = "reputation.record_stale";
    public static final String RECORD_UNAVAILABLE = "reputation.record_unavailable";

    public static final class Result {
        public final Decision decision;
        public final boolean authenticated;
        public final boolean fresh;
        public final List<String> reasons;

        private Result(
                Decision decision,
                boolean authenticated,
                boolean fresh,
                List<String> reasons) {
            this.decision = decision;
            this.authenticated = authenticated;
            this.fresh = fresh;
            this.reasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    private PluginReputationPolicy() {
    }

    public static Result evaluate(PluginReputationState state, long nowMillis) {
        if (nowMillis < 0) {
            throw new IllegalArgumentException("Invalid plugin reputation policy time");
        }
        if (state == null) {
            return result(
                    Decision.NO_ASSERTION,
                    false,
                    false,
                    Collections.singletonList(RECORD_UNAVAILABLE));
        }

        boolean fresh = nowMillis >= state.response.issuedAtMillis
                && nowMillis < state.response.expiresAtMillis;
        return evaluateResponse(state.response, fresh);
    }

    private static Result evaluateResponse(PluginReputationResponse response, boolean fresh) {
        ArrayList<String> reasons = new ArrayList<>();

        if (response.archiveStatus == PluginReputationResponse.ArchiveStatus.MALICIOUS) {
            reasons.add(BLOCK_ARCHIVE);
        }
        if (response.keyStatus == PluginReputationResponse.KeyStatus.REVOKED) {
            reasons.add(BLOCK_KEY);
        }

        if (!reasons.isEmpty()) {
            return result(Decision.BLOCK, true, fresh, reasons);
        }
        if (!fresh) {
            reasons.add(RECORD_STALE);
        }

        return result(Decision.NO_ASSERTION, true, fresh, reasons);
    }

    private static Result result(
            Decision decision,
            boolean authenticated,
            boolean fresh,
            List<String> reasons) {
        return new Result(decision, authenticated, fresh, reasons);
    }
}
