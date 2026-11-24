package com.arnavjot.glimpse.server.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("glimpse")
public record GlimpseProperties(
        String mediaBaseUrl,
        String seedManifest,
        int feedPageMax,
        int feedCacheTtlSeconds,
        int eventsBatchMax) {

    public String mediaUrl(String relativePath) {
        String base = mediaBaseUrl.endsWith("/") ? mediaBaseUrl.substring(0, mediaBaseUrl.length() - 1) : mediaBaseUrl;
        return base + "/" + relativePath;
    }
}
