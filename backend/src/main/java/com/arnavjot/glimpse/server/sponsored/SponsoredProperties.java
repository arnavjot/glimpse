package com.arnavjot.glimpse.server.sponsored;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param slot         a sponsored clip goes after this many clips on each page (0: off)
 * @param dailyCap     most sponsored impressions one device sees in 24 hours
 * @param seedManifest creatives to load on first start (scripts/make_ads.sh writes it)
 */
@ConfigurationProperties("glimpse.sponsored")
public record SponsoredProperties(int slot, int dailyCap, String seedManifest) {}
