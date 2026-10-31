package org.telegram.messenger.plugins.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface PluginPythonRiskScanner {
    int MAX_SOURCES = 128;
    int MAX_SOURCE_BYTES = 256 * 1024;
    int MAX_TOTAL_SOURCE_BYTES = 2 * 1024 * 1024;
    int MAX_FINDINGS = 64;
    int MAX_LIMITATIONS = 8;

    Set<String> CATEGORIES = categories();

    Result scan(List<Source> sources) throws IOException;

    final class Source {
        public final String path;
        private final byte[] data;

        public Source(String path, byte[] data) {
            if (path == null
                    || path.isEmpty()
                    || path.getBytes(StandardCharsets.UTF_8).length > 240
                    || data == null
                    || data.length > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("Invalid Python risk source");
            }

            new PluginRiskEvidence(
                    "analysis.incomplete",
                    path,
                    PluginRiskEvidence.Source.PYTHON,
                    PluginRiskEvidence.Confidence.EXACT);

            this.path = path;
            this.data = data.clone();
        }

        public int size() {
            return data.length;
        }

        public byte[] bytes() {
            return data.clone();
        }
    }

    final class Finding {
        public final String id;
        public final String category;
        public final String detail;
        public final PluginRiskEvidence.Confidence confidence;

        public Finding(
                String id,
                String category,
                String detail,
                PluginRiskEvidence.Confidence confidence) {
            if (!PluginRiskEvidence.isValidId(id)
                    || (category != null && !CATEGORIES.contains(category))
                    || confidence == null) {
                throw new IllegalArgumentException("Invalid Python risk finding");
            }

            new PluginRiskEvidence(id, detail, PluginRiskEvidence.Source.PYTHON, confidence);
            this.id = id;
            this.category = category;
            this.detail = detail;
            this.confidence = confidence;
        }
    }

    final class Result {
        public final List<Finding> findings;
        public final List<String> limitations;

        public Result(List<Finding> findings, List<String> limitations) {
            if (findings == null
                    || limitations == null
                    || findings.size() > MAX_FINDINGS
                    || limitations.size() > MAX_LIMITATIONS) {
                throw new IllegalArgumentException("Invalid Python risk result");
            }

            ArrayList<Finding> findingCopy = new ArrayList<>(findings);
            ArrayList<String> limitationCopy = new ArrayList<>(limitations);

            if (findingCopy.contains(null) || limitationCopy.contains(null)) {
                throw new IllegalArgumentException("Invalid Python risk result item");
            }

            for (String limitation : limitationCopy) {
                if (!limitation.equals("analysis.unsupported_python")
                        && !limitation.equals("analysis.limit_exceeded")
                        && !limitation.equals("analysis.incomplete")) {
                    throw new IllegalArgumentException("Invalid Python risk limitation");
                }
            }

            this.findings = Collections.unmodifiableList(findingCopy);
            this.limitations = Collections.unmodifiableList(limitationCopy);
        }
    }

    static Set<String> categories() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(
                result,
                "network",
                "filesystem",
                "process",
                "java_bridge",
                "dynamic_execution",
                "host_internals",
                "hooks",
                "android_ui",
                "unmanaged_threads",
                "native_code");
        return Collections.unmodifiableSet(result);
    }
}
