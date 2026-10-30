package org.telegram.messenger.plugins.risk;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PluginRiskEvidence implements Comparable<PluginRiskEvidence> {
    public enum Confidence {
        EXACT,
        HEURISTIC,
        UNKNOWN
    }

    public enum Source {
        MANIFEST,
        ARCHIVE,
        DEX,
        PYTHON,
        RUNTIME,
        REPUTATION
    }

    public final String id;
    public final String detail;
    public final Source source;
    public final Confidence confidence;

    public PluginRiskEvidence(String id, String detail, Source source, Confidence confidence) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid risk evidence ID");
        }
        if (detail != null && (!isValidText(detail)
                || detail.getBytes(StandardCharsets.UTF_8).length > 256)) {
            throw new IllegalArgumentException("Invalid risk evidence detail");
        }
        if (source == null || confidence == null) {
            throw new IllegalArgumentException("Risk evidence source and confidence are required");
        }

        this.id = id;
        this.detail = detail;
        this.source = source;
        this.confidence = confidence;
    }

    static boolean isValidId(String value) {
        return value != null
                && value.length() <= 128
                && value.matches("[a-z][a-z0-9_.-]*");
    }

    private static boolean isValidText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);

            if (Character.isISOControl(current)) {
                return false;
            }
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }

                i++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int compareTo(PluginRiskEvidence other) {
        int result = id.compareTo(other.id);

        if (result != 0) {
            return result;
        }

        result = compareNullable(detail, other.detail);

        if (result != 0) {
            return result;
        }

        result = source.compareTo(other.source);

        if (result != 0) {
            return result;
        }

        return confidence.compareTo(other.confidence);
    }

    private static int compareNullable(String first, String second) {
        if (first == null) {
            return second == null ? 0 : -1;
        }
        if (second == null) {
            return 1;
        }

        return first.compareTo(second);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginRiskEvidence)) {
            return false;
        }

        PluginRiskEvidence other = (PluginRiskEvidence) object;
        return id.equals(other.id)
                && Objects.equals(detail, other.detail)
                && source == other.source
                && confidence == other.confidence;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, detail, source, confidence);
    }
}
