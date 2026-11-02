package org.telegram.messenger.plugins.journal;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Objects;

public final class PluginRuntimeJournalEvent {
    public static final class SubjectToken {
        private static final SecureRandom RANDOM = new SecureRandom();

        private final long high;
        private final long low;

        private SubjectToken(long high, long low) {
            if (high == 0 && low == 0) {
                throw new IllegalArgumentException("Invalid plugin runtime journal subject token");
            }

            this.high = high;
            this.low = low;
        }

        public static SubjectToken create() {
            long high;
            long low;

            do {
                high = RANDOM.nextLong();
                low = RANDOM.nextLong();
            } while (high == 0 && low == 0);

            return new SubjectToken(high, low);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof SubjectToken)) {
                return false;
            }

            SubjectToken other = (SubjectToken) object;
            return high == other.high && low == other.low;
        }

        @Override
        public int hashCode() {
            return Objects.hash(high, low);
        }

        static SubjectToken decode(long high, long low) {
            if (high == 0 && low == 0) {
                return null;
            }

            return new SubjectToken(high, low);
        }

        long high() {
            return high;
        }

        long low() {
            return low;
        }
    }

    public enum DurationBucket {
        UNDER_ONE_MILLISECOND(1),
        UNDER_FOUR_MILLISECONDS(2),
        UNDER_SIXTEEN_MILLISECONDS(3),
        UNDER_ONE_HUNDRED_MILLISECONDS(4),
        AT_LEAST_ONE_HUNDRED_MILLISECONDS(5);

        public final int code;

        DurationBucket(int code) {
            this.code = code;
        }
    }

    public final long sequence;
    public final long monotonicTimeMillis;
    public final long processInstanceId;
    public final long runtimeInstanceId;
    public final String pluginId;
    public final String archiveSha256;
    public final long generation;
    public final PluginRuntimeJournalCatalog.Kind kind;
    public final SubjectToken subjectToken;
    public final int value;

    public PluginRuntimeJournalEvent(
            long sequence,
            long monotonicTimeMillis,
            long processInstanceId,
            long runtimeInstanceId,
            String pluginId,
            String archiveSha256,
            long generation,
            PluginRuntimeJournalCatalog.Kind kind,
            SubjectToken subjectToken,
            int value) {
        if (sequence <= 0
                || monotonicTimeMillis < 0
                || processInstanceId <= 0
                || runtimeInstanceId <= 0
                || !isPluginId(pluginId)
                || !isSha256(archiveSha256)
                || generation <= 0
                || kind == null
                || !isValidFields(kind, subjectToken, value)) {
            throw new IllegalArgumentException("Invalid plugin runtime journal event");
        }

        this.sequence = sequence;
        this.monotonicTimeMillis = monotonicTimeMillis;
        this.processInstanceId = processInstanceId;
        this.runtimeInstanceId = runtimeInstanceId;
        this.pluginId = pluginId;
        this.archiveSha256 = archiveSha256;
        this.generation = generation;
        this.kind = kind;
        this.subjectToken = subjectToken;
        this.value = value;
    }

    public PluginRuntimeJournalCatalog.Category category() {
        return kind.category;
    }

    public DurationBucket durationBucket() {
        if (kind.valueType != PluginRuntimeJournalCatalog.ValueType.DURATION_BUCKET) {
            return null;
        }

        return durationBucket(value);
    }

    public boolean sameRuntime(PluginRuntimeJournalEvent other) {
        return other != null
                && processInstanceId == other.processInstanceId
                && runtimeInstanceId == other.runtimeInstanceId
                && generation == other.generation
                && pluginId.equals(other.pluginId)
                && archiveSha256.equals(other.archiveSha256);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginRuntimeJournalEvent)) {
            return false;
        }

        PluginRuntimeJournalEvent other = (PluginRuntimeJournalEvent) object;
        return sequence == other.sequence
                && monotonicTimeMillis == other.monotonicTimeMillis
                && processInstanceId == other.processInstanceId
                && runtimeInstanceId == other.runtimeInstanceId
                && generation == other.generation
                && Objects.equals(subjectToken, other.subjectToken)
                && value == other.value
                && pluginId.equals(other.pluginId)
                && archiveSha256.equals(other.archiveSha256)
                && kind == other.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sequence,
                monotonicTimeMillis,
                processInstanceId,
                runtimeInstanceId,
                pluginId,
                archiveSha256,
                generation,
                kind,
                subjectToken,
                value);
    }

    static DurationBucket durationBucket(int code) {
        for (DurationBucket bucket : DurationBucket.values()) {
            if (bucket.code == code) {
                return bucket;
            }
        }

        return null;
    }

    static boolean isPluginId(String value) {
        return value != null
                && value.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                && value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean isValidFields(
            PluginRuntimeJournalCatalog.Kind kind,
            SubjectToken subjectToken,
            int value) {
        if (kind.requiresSubject != (subjectToken != null)) {
            return false;
        }

        switch (kind.valueType) {
            case NONE:
                return value == 0;
            case COUNT:
                return value > 0;
            case DURATION_BUCKET:
                return durationBucket(value) != null;
            default:
                return false;
        }
    }
}
