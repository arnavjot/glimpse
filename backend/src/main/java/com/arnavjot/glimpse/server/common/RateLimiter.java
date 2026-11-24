package com.arnavjot.glimpse.server.common;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Token-bucket rate limits in Redis, shared by every API instance.
 *
 * <ul>
 *   <li><b>Token bucket</b>: a bucket holds up to {@code capacity} tokens and refills steadily
 *       over {@code period}. Each request takes one. Short bursts are fine; a steady flood is
 *       not. Unlike a fixed window, there is no edge where twice the limit gets through.</li>
 *   <li><b>Atomic</b>: read, refill, take and write happen in one Lua script, so two requests
 *       racing for the last token can't both get it. Time comes from Redis, not from each
 *       API instance's clock.</li>
 *   <li><b>Fails open</b>: if Redis is unreachable the request is allowed (and counted in
 *       {@code glimpse.ratelimit.unavailable}). Limits protect the service; they shouldn't be
 *       the reason it goes down.</li>
 * </ul>
 */
@Component
public class RateLimiter {

    /** A limit: up to {@code capacity} requests at once, refilled over {@code period}. */
    public record Limit(String name, int capacity, Duration period) {}

    /** Thrown when a limit is hit; ApiErrors turns it into 429 with Retry-After. */
    public static class LimitedException extends ResponseStatusException {
        public final long retryAfterSeconds;

        public LimitedException(String reason, long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, reason);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    // KEYS[1] bucket; ARGV capacity, refill per ms. Returns {allowed (1/0), ms until a token}.
    private static final DefaultRedisScript<List> TAKE = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(b[1]) or capacity
            local ts = tonumber(b[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
            return {allowed, wait}
            """, List.class);

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public RateLimiter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    /**
     * Takes one token from {@code limit}'s bucket for {@code who} (an IP, a user id).
     *
     * @throws LimitedException with a human message and Retry-After when the bucket is empty
     */
    public void take(Limit limit, String who, String what) {
        double perMs = limit.capacity() / (double) limit.period().toMillis();
        List<?> r;
        try {
            r = redis.execute(TAKE, List.of("rl:" + limit.name() + ":" + who),
                    String.valueOf(limit.capacity()), String.valueOf(perMs));
        } catch (RuntimeException e) {
            meters.counter("glimpse.ratelimit.unavailable", "limit", limit.name()).increment();
            log.warn("Rate limit {} unavailable (Redis): allowing the request", limit.name(), e);
            return;
        }
        if (r != null && ((Number) r.get(0)).longValue() == 1) return;
        long waitMs = r == null ? 0 : ((Number) r.get(1)).longValue();
        long seconds = Math.max(1, (waitMs + 999) / 1000);
        meters.counter("glimpse.ratelimit.rejected", "limit", limit.name()).increment();
        throw new LimitedException("too many " + what + ", try again in " + human(seconds), seconds);
    }

    static String human(long seconds) {
        if (seconds < 60) return seconds == 1 ? "a second" : seconds + " seconds";
        long minutes = (seconds + 59) / 60;
        return minutes == 1 ? "a minute" : minutes + " minutes";
    }
}
