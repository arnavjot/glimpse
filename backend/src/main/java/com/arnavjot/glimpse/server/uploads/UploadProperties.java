package com.arnavjot.glimpse.server.uploads;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxBytes   largest original accepted
 * @param maxSeconds clips are cut to this length
 * @param urlMinutes   lifetime of a pre-signed upload URL
 * @param abandonHours an upload never completed in this time is cleaned up
 * @param failedDays   failed uploads are kept this long, then cleaned up
 */
@ConfigurationProperties("glimpse.uploads")
public record UploadProperties(long maxBytes, int maxSeconds, int urlMinutes, int abandonHours, int failedDays,
                               int maxInProgress) {}
