package com.manara.backend.video.provider;

import java.net.URI;
import java.util.Locale;

/**
 * Small shared helpers for reading a video URL. Package-private to the adapters, so host handling
 * is written once and every provider judges a host the same way.
 */
final class VideoUris {

    private VideoUris() {
    }

    /**
     * The host with its subdomain-of-convenience removed and lower-cased, so {@code www.vimeo.com},
     * {@code m.youtube.com} and {@code VIMEO.COM} all reduce to the name an adapter checks against.
     *
     * <p>Returns an empty string for a URI with no host, which no adapter's host set contains — a
     * URL like {@code file:///video} is therefore unsupported rather than a match.
     */
    static String bareHost(URI uri) {
        String host = uri.getHost();
        if (host == null) return "";

        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) return host.substring(4);
        if (host.startsWith("m.")) return host.substring(2);
        return host;
    }

    /** The first value of a query parameter, or null when the URL does not carry it. */
    static String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) return null;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equals(name)) {
                String value = pair.substring(eq + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
