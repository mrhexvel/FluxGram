package org.telegram.messenger.plugins.reputation;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public final class PluginReputationEndpoint {
    public static final String QUERY_PATH = "/v1/plugin-reputation/query";

    public final String baseUrl;
    public final String authorityKeyId;

    private final URL queryUrl;

    private PluginReputationEndpoint(String baseUrl, String authorityKeyId, URL queryUrl) {
        this.baseUrl = baseUrl;
        this.authorityKeyId = authorityKeyId;
        this.queryUrl = queryUrl;
    }

    public static PluginReputationEndpoint create(String baseUrl, String authorityKeyId) {
        if (baseUrl == null || authorityKeyId == null) {
            throw new IllegalArgumentException("Plugin reputation endpoint values must not be null");
        }

        String normalizedBaseUrl = baseUrl.trim();
        String normalizedAuthorityKeyId = authorityKeyId.trim();

        if (normalizedBaseUrl.isEmpty() && normalizedAuthorityKeyId.isEmpty()) {
            return new PluginReputationEndpoint("", "", null);
        }
        if (normalizedBaseUrl.isEmpty()
                || normalizedAuthorityKeyId.isEmpty()
                || !PluginReputationResponse.isKeyId(normalizedAuthorityKeyId)) {
            throw new IllegalArgumentException("Invalid plugin reputation endpoint configuration");
        }

        URI uri;

        try {
            uri = new URI(normalizedBaseUrl);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid plugin reputation endpoint URL", error);
        }

        String host = uri.getHost();
        String path = uri.getRawPath();
        int port = uri.getPort();

        if (!"https".equals(uri.getScheme())
                || host == null
                || host.isEmpty()
                || !host.equals(host.toLowerCase(java.util.Locale.US))
                || !isAsciiHost(host)
                || !isDomainHost(host)
                || "localhost".equals(host)
                || host.endsWith(".")
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || port != -1 && port != 443
                || path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("Invalid plugin reputation endpoint URL");
        }

        String expectedAuthority = port == 443 ? host + ":443" : host;

        if (!expectedAuthority.equals(uri.getRawAuthority())) {
            throw new IllegalArgumentException("Ambiguous plugin reputation endpoint authority");
        }

        String origin = "https://" + expectedAuthority;

        try {
            return new PluginReputationEndpoint(
                    origin,
                    normalizedAuthorityKeyId,
                    new URL(origin + QUERY_PATH));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid plugin reputation endpoint URL", error);
        }
    }

    public boolean isEnabled() {
        return queryUrl != null;
    }

    public URL queryUrl() {
        if (!isEnabled()) {
            throw new IllegalStateException("Plugin reputation endpoint is disabled");
        }

        return queryUrl;
    }

    private static boolean isAsciiHost(String host) {
        for (int i = 0; i < host.length(); i++) {
            char character = host.charAt(i);

            if (character > 127) {
                return false;
            }
        }

        return true;
    }

    private static boolean isDomainHost(String host) {
        if (host.indexOf(':') >= 0 || host.length() > 253) {
            return false;
        }

        String[] labels = host.split("\\.", -1);
        boolean allNumeric = true;

        if (labels.length < 2) {
            return false;
        }

        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.startsWith("xn--")
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                return false;
            }

            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);

                if (character < '0' || character > '9') {
                    allNumeric = false;
                }
                if ((character < 'a' || character > 'z')
                        && (character < '0' || character > '9')
                        && character != '-') {
                    return false;
                }
            }
        }

        return !allNumeric;
    }
}
