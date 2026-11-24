package com.arnavjot.glimpse.server.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limits, in application.yml under glimpse.rate-limits. Each one allows {@code capacity}
 * requests in a burst, refilled steadily over {@code minutes}.
 */
@ConfigurationProperties("glimpse.rate-limits")
public record RateLimitProperties(Spec loginPerIp, Spec registerPerIp, Spec refreshPerIp, Spec uploadsPerAccount) {

    public record Spec(int capacity, int minutes) {}

    public RateLimiter.Limit login() {
        return limit("login-ip", loginPerIp);
    }

    public RateLimiter.Limit register() {
        return limit("register-ip", registerPerIp);
    }

    public RateLimiter.Limit refresh() {
        return limit("refresh-ip", refreshPerIp);
    }

    public RateLimiter.Limit uploads() {
        return limit("uploads-user", uploadsPerAccount);
    }

    private static RateLimiter.Limit limit(String name, Spec s) {
        return new RateLimiter.Limit(name, s.capacity(), Duration.ofMinutes(s.minutes()));
    }
}
