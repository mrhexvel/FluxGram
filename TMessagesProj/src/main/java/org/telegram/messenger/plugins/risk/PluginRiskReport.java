package org.telegram.messenger.plugins.risk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class PluginRiskReport {
    public static final int FORMAT_VERSION = 1;

    private static final int MAGIC = 0x46475252;
    private static final int MAX_ENCODED_BYTES = 64 * 1024;
    private static final int MAX_MODULES = 8;
    private static final int MAX_EVIDENCE = 128;
    private static final int MAX_LIMITATIONS = 32;

    public static final class ModuleDigest implements Comparable<ModuleDigest> {
        public final String role;
        public final String sha256;

        public ModuleDigest(String role, String sha256) {
            if (!PluginRiskEvidence.isValidId(role) || !isSha256(sha256)) {
                throw new IllegalArgumentException("Invalid module digest");
            }

            this.role = role;
            this.sha256 = sha256;
        }

        @Override
        public int compareTo(ModuleDigest other) {
            return role.compareTo(other.role);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ModuleDigest)) {
                return false;
            }

            ModuleDigest other = (ModuleDigest) object;
            return role.equals(other.role) && sha256.equals(other.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(role, sha256);
        }
    }

    public final String archiveSha256;
    public final String pluginId;
    public final String pluginVersion;
    public final int packageSchemaVersion;
    public final int analyzerVersion;
    public final String hostFingerprint;
    public final long analyzedAtMillis;
    public final List<ModuleDigest> modules;
    public final List<PluginRiskEvidence> evidence;
    public final List<String> limitations;

    public PluginRiskReport(
            String archiveSha256,
            String pluginId,
            String pluginVersion,
            int packageSchemaVersion,
            int analyzerVersion,
            String hostFingerprint,
            long analyzedAtMillis,
            List<ModuleDigest> modules,
            List<PluginRiskEvidence> evidence,
            List<String> limitations) {
        if (!isSha256(archiveSha256)) {
            throw new IllegalArgumentException("Invalid archive digest");
        }
        if (pluginId == null
                || pluginVersion == null
                || !pluginId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                || pluginId.getBytes(StandardCharsets.UTF_8).length > 128
                || !pluginVersion.matches(
                        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                || pluginVersion.getBytes(StandardCharsets.UTF_8).length > 64) {
            throw new IllegalArgumentException("Invalid plugin identity");
        }
        if (packageSchemaVersion <= 0 || analyzerVersion <= 0 || analyzedAtMillis < 0) {
            throw new IllegalArgumentException("Invalid risk report version metadata");
        }
        if (!isSha256(hostFingerprint)) {
            throw new IllegalArgumentException("Invalid host fingerprint");
        }

        this.archiveSha256 = archiveSha256;
        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.packageSchemaVersion = packageSchemaVersion;
        this.analyzerVersion = analyzerVersion;
        this.hostFingerprint = hostFingerprint;
        this.analyzedAtMillis = analyzedAtMillis;
        this.modules = normalizeModules(modules);
        this.evidence = normalizeEvidence(evidence);
        this.limitations = normalizeLimitations(limitations);
    }

    public boolean hasEvidence(String id) {
        for (PluginRiskEvidence item : evidence) {
            if (item.id.equals(id)) {
                return true;
            }
        }

        return false;
    }

    public PluginRiskCatalog.Severity maximumSeverity() {
        PluginRiskCatalog.Severity maximum = PluginRiskCatalog.Severity.INFORMATIONAL;

        for (PluginRiskEvidence item : evidence) {
            PluginRiskCatalog.Severity severity = PluginRiskCatalog.resolve(item.id).severity;

            if (severity.compareTo(maximum) > 0) {
                maximum = severity;
            }
        }

        for (String limitation : limitations) {
            PluginRiskCatalog.Severity severity = PluginRiskCatalog.resolve(limitation).severity;

            if (severity.compareTo(maximum) > 0) {
                maximum = severity;
            }
        }

        return maximum;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, archiveSha256, 64);
            writeString(output, pluginId, 128);
            writeString(output, pluginVersion, 64);
            output.writeInt(packageSchemaVersion);
            output.writeInt(analyzerVersion);
            writeNullableString(output, hostFingerprint, 128);
            output.writeLong(analyzedAtMillis);
            output.writeInt(modules.size());

            for (ModuleDigest module : modules) {
                writeString(output, module.role, 128);
                writeString(output, module.sha256, 64);
            }

            output.writeInt(evidence.size());

            for (PluginRiskEvidence item : evidence) {
                writeString(output, item.id, 128);
                writeNullableString(output, item.detail, 256);
                output.writeInt(item.source.ordinal());
                output.writeInt(item.confidence.ordinal());
            }

            output.writeInt(limitations.size());

            for (String limitation : limitations) {
                writeString(output, limitation, 128);
            }
        }

        byte[] encoded = bytes.toByteArray();

        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Risk report exceeds encoded size limit");
        }

        return encoded;
    }

    public static PluginRiskReport decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("Invalid risk report size");
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid risk report header");
            }
            if (input.readInt() != FORMAT_VERSION) {
                throw new IOException("Unsupported risk report version");
            }

            String archiveSha256 = readString(input, 64);
            String pluginId = readString(input, 128);
            String pluginVersion = readString(input, 64);
            int packageSchemaVersion = input.readInt();
            int analyzerVersion = input.readInt();
            String hostFingerprint = readNullableString(input, 128);
            long analyzedAtMillis = input.readLong();
            int moduleCount = readCount(input, MAX_MODULES);
            ArrayList<ModuleDigest> modules = new ArrayList<>(moduleCount);

            for (int i = 0; i < moduleCount; i++) {
                modules.add(new ModuleDigest(
                        readString(input, 128),
                        readString(input, 64)));
            }

            int evidenceCount = readCount(input, MAX_EVIDENCE);
            ArrayList<PluginRiskEvidence> evidence = new ArrayList<>(evidenceCount);

            for (int i = 0; i < evidenceCount; i++) {
                String id = readString(input, 128);
                String detail = readNullableString(input, 256);
                PluginRiskEvidence.Source source = readEnum(
                        input,
                        PluginRiskEvidence.Source.values());
                PluginRiskEvidence.Confidence confidence = readEnum(
                        input,
                        PluginRiskEvidence.Confidence.values());
                evidence.add(new PluginRiskEvidence(id, detail, source, confidence));
            }

            int limitationCount = readCount(input, MAX_LIMITATIONS);
            ArrayList<String> limitations = new ArrayList<>(limitationCount);

            for (int i = 0; i < limitationCount; i++) {
                limitations.add(readString(input, 128));
            }

            if (input.read() != -1) {
                throw new IOException("Trailing risk report data");
            }

            return new PluginRiskReport(
                    archiveSha256,
                    pluginId,
                    pluginVersion,
                    packageSchemaVersion,
                    analyzerVersion,
                    hostFingerprint,
                    analyzedAtMillis,
                    modules,
                    evidence,
                    limitations);
        } catch (EOFException error) {
            throw new IOException("Truncated risk report", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid risk report content", error);
        }
    }

    private static List<ModuleDigest> normalizeModules(List<ModuleDigest> modules) {
        if (modules == null || modules.size() > MAX_MODULES) {
            throw new IllegalArgumentException("Invalid module digest list");
        }

        ArrayList<ModuleDigest> result = new ArrayList<>(modules);

        if (result.contains(null)) {
            throw new IllegalArgumentException("Null module digest");
        }

        Collections.sort(result);

        for (int i = 1; i < result.size(); i++) {
            if (result.get(i - 1).role.equals(result.get(i).role)) {
                throw new IllegalArgumentException("Duplicate module digest role");
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static List<PluginRiskEvidence> normalizeEvidence(List<PluginRiskEvidence> evidence) {
        if (evidence == null || evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException("Invalid risk evidence list");
        }

        ArrayList<PluginRiskEvidence> sorted = new ArrayList<>(evidence);

        if (sorted.contains(null)) {
            throw new IllegalArgumentException("Null risk evidence");
        }

        Collections.sort(sorted);
        ArrayList<PluginRiskEvidence> result = new ArrayList<>();

        for (PluginRiskEvidence item : sorted) {
            if (!result.isEmpty() && sameEvidenceIdentity(result.get(result.size() - 1), item)) {
                result.set(result.size() - 1, item);
            } else {
                result.add(item);
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static boolean sameEvidenceIdentity(
            PluginRiskEvidence first,
            PluginRiskEvidence second) {
        if (!first.id.equals(second.id) || first.source != second.source) {
            return false;
        }

        return first.detail == null
                ? second.detail == null
                : first.detail.equals(second.detail);
    }

    private static List<String> normalizeLimitations(List<String> limitations) {
        if (limitations == null || limitations.size() > MAX_LIMITATIONS) {
            throw new IllegalArgumentException("Invalid risk limitation list");
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();

        for (String limitation : limitations) {
            if (!PluginRiskEvidence.isValidId(limitation)) {
                throw new IllegalArgumentException("Invalid risk limitation ID");
            }

            unique.add(limitation);
        }

        ArrayList<String> result = new ArrayList<>(unique);
        result.sort(Comparator.naturalOrder());

        return Collections.unmodifiableList(result);
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void writeNullableString(
            DataOutputStream output,
            String value,
            int maxBytes) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }

        writeString(output, value, maxBytes);
    }

    private static void writeString(
            DataOutputStream output,
            String value,
            int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > maxBytes) {
            throw new IOException("Risk report string exceeds limit");
        }

        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readNullableString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();

        if (length == -1) {
            return null;
        }

        return readString(input, length, maxBytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        return readString(input, input.readInt(), maxBytes);
    }

    private static String readString(
            DataInputStream input,
            int length,
            int maxBytes) throws IOException {
        if (length < 0 || length > maxBytes) {
            throw new IOException("Invalid risk report string length");
        }

        byte[] bytes = new byte[length];
        input.readFully(bytes);

        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Invalid risk report UTF-8", error);
        }
    }

    private static int readCount(DataInputStream input, int maximum) throws IOException {
        int count = input.readInt();

        if (count < 0 || count > maximum) {
            throw new IOException("Invalid risk report count");
        }

        return count;
    }

    private static <T> T readEnum(DataInputStream input, T[] values) throws IOException {
        int ordinal = input.readInt();

        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("Invalid risk report enum");
        }

        return values[ordinal];
    }
}
