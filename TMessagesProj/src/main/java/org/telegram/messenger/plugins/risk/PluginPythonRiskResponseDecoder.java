package org.telegram.messenger.plugins.risk;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PluginPythonRiskResponseDecoder {
    private static final Set<String> FINDING_IDS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "python.internal_runtime_import",
                    "bridge.java_direct",
                    "host.telegram_internal_direct",
                    "host.plugin_internal_direct",
                    "hook.direct_xposed",
                    "hook.direct_aliuhook",
                    "network.raw",
                    "network.endpoint_literal",
                    "filesystem.unmanaged",
                    "process.shell_execution",
                    "thread.unmanaged",
                    "python.dynamic_execution",
                    "code.native_loading",
                    "ui.direct_android",
                    "analysis.dynamic_target",
                    "analysis.obfuscated_or_dynamic",
                    "python.top_level_side_effect")));

    private PluginPythonRiskResponseDecoder() {
    }

    public static PluginPythonRiskScanner.Result decode(String encoded) throws IOException {
        if (encoded == null || encoded.length() > 64 * 1024) {
            throw new IOException("Invalid Python analysis response");
        }

        int version = -1;
        List<PluginPythonRiskScanner.Finding> findings = null;
        List<String> limitations = null;
        Set<String> fields = new HashSet<>();

        try (JsonReader reader = new JsonReader(new StringReader(encoded))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();

            while (reader.hasNext()) {
                String field = reader.nextName();

                if (!fields.add(field)) {
                    throw new IOException("Duplicate Python analysis field");
                }

                if (field.equals("version")) {
                    if (reader.peek() != JsonToken.NUMBER
                            || !"1".equals(reader.nextString())) {
                        throw new IOException("Invalid Python analysis protocol version");
                    }
                    version = 1;
                } else if (field.equals("findings")) {
                    findings = readFindings(reader);
                } else if (field.equals("limitations")) {
                    limitations = readLimitations(reader);
                } else {
                    throw new IOException("Unknown Python analysis field");
                }
            }

            reader.endObject();

            if (reader.peek() != JsonToken.END_DOCUMENT
                    || version != 1
                    || findings == null
                    || limitations == null) {
                throw new IOException("Invalid Python analysis response");
            }

            return new PluginPythonRiskScanner.Result(findings, limitations);
        } catch (IllegalStateException | IllegalArgumentException error) {
            throw new IOException("Invalid Python analysis response", error);
        }
    }

    private static List<PluginPythonRiskScanner.Finding> readFindings(JsonReader reader)
            throws IOException {
        ArrayList<PluginPythonRiskScanner.Finding> result = new ArrayList<>();
        reader.beginArray();

        while (reader.hasNext()) {
            if (result.size() >= PluginPythonRiskScanner.MAX_FINDINGS) {
                throw new IOException("Too many Python analysis findings");
            }

            String id = null;
            String category = null;
            String detail = null;
            String confidence = null;
            Set<String> fields = new HashSet<>();
            reader.beginObject();

            while (reader.hasNext()) {
                String field = reader.nextName();

                if (!fields.add(field)) {
                    throw new IOException("Duplicate Python finding field");
                }

                if (field.equals("id")) {
                    id = readString(reader, 128, false);
                } else if (field.equals("category")) {
                    category = readString(reader, 32, true);
                } else if (field.equals("detail")) {
                    detail = readString(reader, 256, true);
                } else if (field.equals("confidence")) {
                    confidence = readString(reader, 16, false);
                } else {
                    throw new IOException("Unknown Python finding field");
                }
            }

            reader.endObject();

            if (fields.size() != 4
                    || !FINDING_IDS.contains(id)
                    || (category != null && !PluginPythonRiskScanner.CATEGORIES.contains(category))
                    || !Objects.equals(category, categoryForId(id))) {
                throw new IOException("Unknown Python analysis finding");
            }

            PluginRiskEvidence.Confidence parsedConfidence;

            if ("exact".equals(confidence)) {
                parsedConfidence = PluginRiskEvidence.Confidence.EXACT;
            } else if ("heuristic".equals(confidence)) {
                parsedConfidence = PluginRiskEvidence.Confidence.HEURISTIC;
            } else if ("unknown".equals(confidence)) {
                parsedConfidence = PluginRiskEvidence.Confidence.UNKNOWN;
            } else {
                throw new IOException("Unknown Python finding confidence");
            }

            result.add(new PluginPythonRiskScanner.Finding(
                    id, category, detail, parsedConfidence));
        }

        reader.endArray();
        return result;
    }

    private static List<String> readLimitations(JsonReader reader) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        reader.beginArray();

        while (reader.hasNext()) {
            if (result.size() >= PluginPythonRiskScanner.MAX_LIMITATIONS) {
                throw new IOException("Too many Python analysis limitations");
            }

            String limitation = readString(reader, 128, false);

            if (!limitation.equals("analysis.unsupported_python")
                    && !limitation.equals("analysis.limit_exceeded")
                    && !limitation.equals("analysis.incomplete")) {
                throw new IOException("Unknown Python analysis limitation");
            }

            result.add(limitation);
        }

        reader.endArray();
        return result;
    }

    private static String readString(JsonReader reader, int maxLength, boolean nullable)
            throws IOException {
        if (nullable && reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }

        if (reader.peek() != JsonToken.STRING) {
            throw new IOException("Invalid Python analysis string");
        }

        String value = reader.nextString();

        if (value.length() > maxLength) {
            throw new IOException("Python analysis string exceeds limit");
        }

        return value;
    }

    private static String categoryForId(String id) {
        if (id.equals("network.raw") || id.equals("network.endpoint_literal")) {
            return "network";
        }
        if (id.equals("filesystem.unmanaged")) {
            return "filesystem";
        }
        if (id.equals("process.shell_execution")) {
            return "process";
        }
        if (id.equals("bridge.java_direct")) {
            return "java_bridge";
        }
        if (id.equals("python.dynamic_execution")
                || id.equals("analysis.dynamic_target")
                || id.equals("analysis.obfuscated_or_dynamic")) {
            return "dynamic_execution";
        }
        if (id.equals("python.internal_runtime_import")
                || id.equals("host.telegram_internal_direct")
                || id.equals("host.plugin_internal_direct")) {
            return "host_internals";
        }
        if (id.equals("hook.direct_xposed") || id.equals("hook.direct_aliuhook")) {
            return "hooks";
        }
        if (id.equals("ui.direct_android")) {
            return "android_ui";
        }
        if (id.equals("thread.unmanaged")) {
            return "unmanaged_threads";
        }
        if (id.equals("code.native_loading")) {
            return "native_code";
        }
        if (id.equals("python.top_level_side_effect")) {
            return null;
        }

        return null;
    }
}
