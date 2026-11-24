package com.arnavjot.glimpse.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.regex.Pattern;

/**
 * Share links and deep links for clips.
 *
 * <ul>
 *   <li>Share link: {@code <server>/v/<id>}, a web page with a link preview and an
 *       "Open in Glimpse" button (served by the backend's SharePageController).</li>
 *   <li>Deep link: {@code glimpse://video/<id>}, which opens the clip in the app.</li>
 * </ul>
 *
 * Pure Java (no android.net.Uri), so it is unit-tested on the JVM.
 */
public final class Links {

    public static final String SCHEME = "glimpse";
    public static final String HOST_VIDEO = "video";
    /** Path of the web share page: /v/<id>. */
    private static final String SHARE_PATH = "/v/";

    /** Same rule as the backend: ids are short slugs. Anything else is not a clip link. */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Links() {
    }

    /**
     * The clip id in a link that opened the app, or null if it isn't a clip link:
     * {@code glimpse://video/<id>} (deep link) or {@code https://<share host>/v/<id>}
     * (App Link; Android only delivers those for the verified share host).
     */
    @Nullable
    public static String videoIdFromDeepLink(@Nullable String link) {
        if (link == null) return null;
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        String path = uri.getPath();
        if (path == null) return null;
        String id;
        if (SCHEME.equalsIgnoreCase(scheme) && HOST_VIDEO.equalsIgnoreCase(uri.getHost())) {
            id = path.startsWith("/") ? path.substring(1) : path;
        } else if ("https".equalsIgnoreCase(scheme) && path.startsWith(SHARE_PATH)) {
            id = path.substring(SHARE_PATH.length());
        } else {
            return null;
        }
        if (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        return isValidVideoId(id) ? id : null;
    }

    /**
     * The clip id in a Play Store install referrer, e.g.
     * {@code utm_source=share&utm_medium=link&video_id=s001} (the backend's share page
     * puts it there), or null. This is the deferred part of a deep link: it survives the
     * install, and the first launch reads it back.
     */
    @Nullable
    public static String videoIdFromReferrer(@Nullable String referrer) {
        if (referrer == null || referrer.isEmpty()) return null;
        for (String pair : referrer.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!"video_id".equals(decode(pair.substring(0, eq)))) continue;
            String id = decode(pair.substring(eq + 1));
            return isValidVideoId(id) ? id : null;
        }
        return null;
    }

    @Nullable
    private static String decode(@NonNull String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Ids are short slugs (the backend's rule too). */
    public static boolean isValidVideoId(@Nullable String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    /** True only for absolute http(s) URLs with a host: safe to hand to a browser. */
    public static boolean isWebUrl(@Nullable String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    @NonNull
    public static String deepLink(@NonNull String videoId) {
        return SCHEME + "://" + HOST_VIDEO + "/" + videoId;
    }

    /** The web link to share: {@code baseUrl} (with or without a trailing slash) + "v/" + id. */
    @NonNull
    public static String shareUrl(@NonNull String baseUrl, @NonNull String videoId) {
        String base = baseUrl.trim();
        return (base.endsWith("/") ? base : base + "/") + "v/" + videoId;
    }
}
