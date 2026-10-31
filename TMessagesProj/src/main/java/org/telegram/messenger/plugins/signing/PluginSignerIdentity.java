package org.telegram.messenger.plugins.signing;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class PluginSignerIdentity {
    public enum Kind {
        UNSIGNED,
        SIGNED
    }

    public final Kind kind;
    public final String pluginId;
    public final String keyId;
    public final String packageSha256;
    public final long generation;

    private PluginSignerIdentity(
            Kind kind,
            String pluginId,
            String keyId,
            String packageSha256,
            long generation) {
        if (!isPluginId(pluginId)
                || kind == null
                || generation < 0
                || kind == Kind.UNSIGNED
                        && (keyId != null || !isSha256(packageSha256) || generation != 0)
                || kind == Kind.SIGNED && (!isKeyId(keyId) || packageSha256 != null)) {
            throw new IllegalArgumentException("Invalid plugin signer identity");
        }

        this.kind = kind;
        this.pluginId = pluginId;
        this.keyId = keyId;
        this.packageSha256 = packageSha256;
        this.generation = generation;
    }

    public static PluginSignerIdentity unsigned(String pluginId, String packageSha256) {
        return new PluginSignerIdentity(Kind.UNSIGNED, pluginId, null, packageSha256, 0);
    }

    public static PluginSignerIdentity signed(String pluginId, String keyId, long generation) {
        return new PluginSignerIdentity(Kind.SIGNED, pluginId, keyId, null, generation);
    }

    static boolean isPluginId(String value) {
        return value != null
                && value.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
                && value.getBytes(StandardCharsets.UTF_8).length <= 100
                && !value.startsWith("fluxgram.");
    }

    static boolean isKeyId(String value) {
        return value != null && value.matches("p256:[a-f0-9]{64}");
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PluginSignerIdentity)) {
            return false;
        }

        PluginSignerIdentity other = (PluginSignerIdentity) object;
        return kind == other.kind
                && generation == other.generation
                && pluginId.equals(other.pluginId)
                && Objects.equals(keyId, other.keyId)
                && Objects.equals(packageSha256, other.packageSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, pluginId, keyId, packageSha256, generation);
    }
}
