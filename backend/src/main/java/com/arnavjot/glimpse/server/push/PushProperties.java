package com.arnavjot.glimpse.server.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param credentialsFile Firebase service-account JSON; empty or missing: push is off
 * @param adminToken      secret for X-Admin-Token on admin endpoints; empty: they are off
 */
@ConfigurationProperties("glimpse.push")
public record PushProperties(String credentialsFile, String adminToken) {}
