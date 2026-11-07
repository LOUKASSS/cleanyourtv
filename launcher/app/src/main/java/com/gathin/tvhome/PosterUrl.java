package com.gathin.tvhome;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * appletv_shelf's poster URLs, kept free of Android calls so the JVM tests can run them: a larger picture from the CDNs
 * that are known to serve one, and a log-safe form of any URL. Only rules checked on a real TV are here; a service
 * without one keeps the URL its app wrote.
 */
final class PosterUrl {
    private PosterUrl() {
    }

    /** The size asked for when a CDN takes one: the shelf's backdrop at most. */
    static final int W = 1280, H = 720;

    /** Twitch's static-cdn: "...-320x180.jpg", or the "{width}x{height}" template left unfilled. */
    private static final Pattern TWITCH_SIZE = Pattern.compile("-(\\d+)x(\\d+)(\\.(?:jpe?g|png|webp))$");
    private static final Pattern TWITCH_TEMPLATE = Pattern.compile("(?:\\{|%7B)width(?:\\}|%7D)x(?:\\{|%7B)height(?:\\}|%7D)",
            Pattern.CASE_INSENSITIVE);

    /** YouTube's still sizes below 1280x720 (default 120x90 up to sddefault 640x480); maxresdefault is 1280x720. */
    private static final Pattern YOUTUBE_STILL = Pattern.compile("^(/vi(?:_webp)?/[^/]+/)(?:default|mqdefault|hqdefault|sddefault)(\\.(?:jpg|webp))$");
    /** Max (HBO Max)'s image CDN serves only some widths: 1000 and 1920 among those above 960 (400 for 1280). */
    private static final int MAX_WIDTH = 1920;

    /**
     * The URL of a larger copy of this poster where its CDN serves one, else uri itself. Each rule was checked on the
     * TCL G08 against the CDN (the larger size loads, and is larger): Twitch, YouTube, Disney+ and Max. Netflix's URLs
     * are signed and carry no size; Canal+, Prime Video, Apple TV and Rakuten already send 1920 px or more.
     */
    static String upgrade(String uri) {
        if (uri == null) return null;
        String host = host(uri);
        if (host == null || !uri.startsWith("https://")) return uri;
        if (host.endsWith(".jtvnw.net")) return twitch(uri);
        if (host.equals("i.ytimg.com")) return youtube(uri);
        if (host.equals("disney.images.edge.bamgrid.com")) return disney(uri);
        if (host.equals("images.cdn.prd.api.discomax.com")) return max(uri);
        return uri;
    }

    private static String youtube(String uri) {
        int q = queryStart(uri), p = uri.indexOf('/', uri.indexOf("://") + 3);
        if (p < 0 || p > q) return uri;
        Matcher m = YOUTUBE_STILL.matcher(uri.substring(p, q));
        if (!m.find()) return uri;
        // Not every video has a maxresdefault (404): TvShelf then falls back to the URL the app wrote.
        return uri.substring(0, p) + m.group(1) + "maxresdefault" + m.group(2) + uri.substring(q);
    }

    /** Disney+'s "scale?...&height=306&width=544": a 16:9 size below 1280x720 becomes 1280x720. */
    private static String disney(String uri) {
        long w = param(uri, "width"), h = param(uri, "height");
        if (w <= 0 || h <= 0 || w >= W || Math.abs(w * 9 - h * 16) > 16) return uri;
        return setParam(setParam(uri, "width", String.valueOf(W)), "height", String.valueOf(H));
    }

    /** Max's "...jpeg?w=600&q=85&p=true&f=jpg": a width below 960 becomes 1920, one it is known to serve. */
    private static String max(String uri) {
        long w = param(uri, "w");
        if (w <= 0 || w >= 960) return uri;
        return setParam(uri, "w", String.valueOf(MAX_WIDTH));
    }

    /** A query parameter's value as a number, -1 when it is absent or not a number. */
    static long param(String uri, String name) {
        String v = paramValue(uri, name);
        if (v == null || v.isEmpty() || v.length() > 9) return -1;
        for (int k = 0; k < v.length(); k++) if (v.charAt(k) < '0' || v.charAt(k) > '9') return -1;
        return Long.parseLong(v);
    }

    private static String paramValue(String uri, String name) {
        int q = uri.indexOf('?');
        if (q < 0) return null;
        int end = uri.indexOf('#', q);
        if (end < 0) end = uri.length();
        for (String kv : uri.substring(q + 1, end).split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        return null;
    }

    /** uri with an existing query parameter's value replaced; the order of the others kept. */
    private static String setParam(String uri, String name, String value) {
        int q = uri.indexOf('?');
        if (q < 0) return uri;
        int end = uri.indexOf('#', q);
        if (end < 0) end = uri.length();
        String[] kvs = uri.substring(q + 1, end).split("&", -1);
        for (int k = 0; k < kvs.length; k++) if (kvs[k].startsWith(name + "=")) kvs[k] = name + "=" + value;
        return uri.substring(0, q + 1) + String.join("&", kvs) + uri.substring(end);
    }

    private static String twitch(String uri) {
        int q = queryStart(uri);
        String path = uri.substring(0, q), rest = uri.substring(q);
        Matcher t = TWITCH_TEMPLATE.matcher(path);
        if (t.find()) return t.replaceFirst(W + "x" + H) + rest;
        Matcher m = TWITCH_SIZE.matcher(path);
        if (!m.find()) return uri;
        long w = Long.parseLong(m.group(1)), h = Long.parseLong(m.group(2));
        // Only a 16:9 picture smaller than the backdrop: a box art (2:3) or a larger one stays as it is.
        if (w >= W || h <= 0 || w * 9 != h * 16) return uri;
        return path.substring(0, m.start()) + "-" + W + "x" + H + m.group(3) + rest;
    }

    /** The host of an http(s) URL, lower case, or null. */
    static String host(String uri) {
        int s = uri.indexOf("://");
        if (s < 0) return null;
        int from = s + 3, end = from;
        while (end < uri.length() && "/?#".indexOf(uri.charAt(end)) < 0) end++;
        int at = uri.lastIndexOf('@', end - 1);
        if (at >= from) from = at + 1;
        int port = uri.indexOf(':', from);
        if (port >= 0 && port < end) end = port;
        return end > from ? uri.substring(from, end).toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static int queryStart(String uri) {
        int q = uri.indexOf('?'), h = uri.indexOf('#');
        if (q < 0) q = uri.length();
        if (h >= 0 && h < q) q = h;
        return q;
    }

    /**
     * What a log may say about a URL: host and path, its long ids shortened to "…", and the names of its query
     * parameters without their values (they may hold tokens).
     */
    static String pattern(String uri) {
        if (uri == null) return "null";
        String host = host(uri);
        int s = uri.indexOf("://");
        int q = queryStart(uri);
        int pathFrom = s < 0 ? 0 : uri.indexOf('/', s + 3);
        String path = pathFrom < 0 || pathFrom > q ? "" : uri.substring(pathFrom, q);
        StringBuilder out = new StringBuilder(host == null ? "?" : host);
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty()) continue;
            out.append('/').append(seg.length() > 24 ? seg.substring(0, 6) + "…" + seg.substring(seg.length() - 12) : seg);
        }
        if (q < uri.length() && uri.charAt(q) == '?') {
            int end = uri.indexOf('#', q);
            String query = uri.substring(q + 1, end < 0 ? uri.length() : end);
            out.append(" ?");
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) continue;
                int eq = kv.indexOf('=');
                out.append(' ').append(eq < 0 ? kv : kv.substring(0, eq));
            }
        }
        return out.toString();
    }
}
