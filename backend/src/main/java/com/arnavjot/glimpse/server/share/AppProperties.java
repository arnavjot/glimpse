package com.arnavjot.glimpse.server.share;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Android app, as share links need it.
 *
 * @param packageName        application id, e.g. com.arnavjot.glimpse
 * @param sha256Fingerprints signing-certificate fingerprints for App Links verification
 */
@ConfigurationProperties("glimpse.app")
public record AppProperties(String packageName, List<String> sha256Fingerprints) {

    public AppProperties {
        sha256Fingerprints = sha256Fingerprints == null ? List.of()
                : sha256Fingerprints.stream().map(String::trim).filter(f -> !f.isEmpty()).toList();
    }

    /**
     * The Play Store page, carrying the clip in the install referrer: after a fresh
     * install the app reads it back (Play Install Referrer API) and opens that clip.
     */
    public String playStoreUrl(String videoId) {
        return playStoreUrl(packageName, videoId);
    }

    /** The Play Store page of any app, with the clip in its install referrer. */
    public static String playStoreUrl(String packageName, String videoId) {
        String referrer = "utm_source=share&utm_medium=link&video_id=" + videoId;
        return "https://play.google.com/store/apps/details?id=" + packageName
                + "&referrer=" + URLEncoder.encode(referrer, StandardCharsets.UTF_8);
    }

    /**
     * An Android intent URL: Chrome opens the app on the clip if it is installed and
     * otherwise goes to the Play Store (S.browser_fallback_url).
     */
    public String androidIntentUrl(String videoId) {
        return "intent://video/" + videoId + "#Intent;scheme=glimpse;package=" + packageName
                + ";S.browser_fallback_url=" + URLEncoder.encode(playStoreUrl(videoId), StandardCharsets.UTF_8)
                + ";end";
    }
}
