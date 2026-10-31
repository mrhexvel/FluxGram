package org.telegram.messenger.plugins.signing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PluginSignedPayload {
    public static final int FORMAT_VERSION = 1;
    public static final String SIGNATURE_ENTRY = ".fluxgram-signature";

    private static final int MAGIC = 0x46475350;
    private static final String DOMAIN = "fluxgram.plugin.package";
    private static final int MAX_ENTRIES = 511;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024;
    private static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024 - 512;
    private static final int MAX_PUBLIC_KEY_BYTES = 256;
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public static final class Entry {
        public final String path;
        public final boolean directory;
        public final long size;
        public final String sha256;

        public Entry(String path, boolean directory, long size, String sha256) {
            if (!isValidPath(path)
                    || SIGNATURE_ENTRY.equals(path)
                    || directory && path.length() > 239
                    || size < 0
                    || size > MAX_FILE_BYTES
                    || directory && size != 0
                    || directory && !EMPTY_SHA256.equals(sha256)
                    || "manifest.json".equals(path) && (directory || size > MAX_MANIFEST_BYTES)
                    || !isSha256(sha256)) {
                throw new IllegalArgumentException("Invalid signed package entry");
            }

            this.path = path;
            this.directory = directory;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public final String pluginId;
    public final String pluginVersion;
    public final int packageSchemaVersion;
    public final List<Entry> entries;

    public PluginSignedPayload(
            String pluginId,
            String pluginVersion,
            int packageSchemaVersion,
            List<Entry> entries) {
        if (pluginId == null
                || pluginVersion == null
                || !pluginId.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                || pluginId.getBytes(StandardCharsets.UTF_8).length > 100
                || !pluginVersion.matches(
                        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                || pluginVersion.getBytes(StandardCharsets.UTF_8).length > 64
                || packageSchemaVersion <= 0
                || pluginId.startsWith("fluxgram.")) {
            throw new IllegalArgumentException("Invalid signed package identity");
        }
        if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid signed package entries");
        }

        ArrayList<Entry> sorted = new ArrayList<>(entries);

        if (sorted.contains(null)) {
            throw new IllegalArgumentException("Null signed package entry");
        }

        Collections.sort(sorted, Comparator.comparing(entry -> entry.path));
        long totalBytes = 0;
        boolean hasManifest = false;
        Map<String, String> spellings = new HashMap<>();
        Map<String, Boolean> paths = new HashMap<>();

        for (int i = 0; i < sorted.size(); i++) {
            Entry entry = sorted.get(i);

            if (i > 0 && sorted.get(i - 1).path.equals(entry.path)) {
                throw new IllegalArgumentException("Duplicate signed package entry");
            }

            String prefix = "";

            for (String part : entry.path.split("/")) {
                prefix = prefix.isEmpty() ? part : prefix + "/" + part;
                String previous = spellings.put(prefix.toLowerCase(Locale.ROOT), prefix);

                if (previous != null && !previous.equals(prefix)) {
                    throw new IllegalArgumentException("Case-conflicting signed package entry");
                }
            }

            paths.put(entry.path, entry.directory);
            totalBytes += entry.size;

            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Signed package exceeds size limit");
            }

            hasManifest |= "manifest.json".equals(entry.path) && !entry.directory;
        }

        for (Entry entry : sorted) {
            int slash = entry.path.indexOf('/');

            while (slash >= 0) {
                if (Boolean.FALSE.equals(paths.get(entry.path.substring(0, slash)))) {
                    throw new IllegalArgumentException("File used as a signed package directory");
                }

                slash = entry.path.indexOf('/', slash + 1);
            }
        }

        if (!hasManifest) {
            throw new IllegalArgumentException("Signed package has no manifest");
        }

        this.pluginId = pluginId;
        this.pluginVersion = pluginVersion;
        this.packageSchemaVersion = packageSchemaVersion;
        this.entries = Collections.unmodifiableList(sorted);
    }

    public byte[] encode(int algorithm, byte[] publicKey) {
        if (algorithm <= 0
                || publicKey == null
                || publicKey.length == 0
                || publicKey.length > MAX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("Invalid signed package signer");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, DOMAIN);
            output.writeInt(algorithm);
            output.writeInt(publicKey.length);
            output.write(publicKey);
            output.writeInt(packageSchemaVersion);
            writeString(output, pluginId);
            writeString(output, pluginVersion);
            output.writeInt(entries.size());

            for (Entry entry : entries) {
                writeString(output, entry.path);
                output.writeBoolean(entry.directory);
                output.writeLong(entry.size);
                output.write(hexBytes(entry.sha256));
            }
        } catch (IOException error) {
            throw new IllegalStateException("Could not encode signed payload", error);
        }

        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static boolean isValidPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 240) {
            return false;
        }

        String[] parts = path.split("/", -1);

        if (parts.length > 16) {
            return false;
        }

        for (String part : parts) {
            if (!part.matches("[A-Za-z0-9_.-]+")
                    || part.equals(".")
                    || part.equals("..")
                    || part.endsWith(".")) {
                return false;
            }
        }

        if (parts[0].equalsIgnoreCase("fluxgram")
                || parts[0].equalsIgnoreCase("fluxgram.py")
                || parts[0].equalsIgnoreCase(SIGNATURE_ENTRY)
                || parts[0].equalsIgnoreCase(".fluxgram-source.fgplugin")
                || parts[0].equalsIgnoreCase(".fluxgram-risk-report")
                || parts[0].equalsIgnoreCase(".fluxgram-signer-state")
                || parts[0].equalsIgnoreCase(".fluxgram-signer-journal")) {
            return false;
        }

        return true;
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];

        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }

        return result;
    }
}
