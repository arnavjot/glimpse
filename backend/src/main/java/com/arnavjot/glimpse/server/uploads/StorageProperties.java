package com.arnavjot.glimpse.server.uploads;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint       S3 endpoint as the server reaches it (e.g. http://seaweedfs:8333)
 * @param publicEndpoint S3 endpoint as phones reach it; upload URLs are signed for this host
 */
@ConfigurationProperties("glimpse.storage")
public record StorageProperties(String endpoint, String publicEndpoint, String region,
                                String accessKey, String secretKey, String bucket) {}
