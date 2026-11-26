package com.arnavjot.glimpse.server.feed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.arnavjot.glimpse.server.common.GlimpseProperties;
import com.arnavjot.glimpse.server.common.RedisBreaker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Cache-aside for feed pages in Redis. TTLs are jittered so pages cached at the
 * same moment don't all expire together, and any Redis failure (down, slow,
 * timed out) is a cache miss: the feed is served from Postgres instead of failing.
 */
@Component
public class FeedCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final int ttlSeconds;
    private final MeterRegistry meters;

    private final RedisBreaker breaker;

    public FeedCache(StringRedisTemplate redis, ObjectMapper json, GlimpseProperties props, MeterRegistry meters,
                     RedisBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
        this.json = json;
        this.ttlSeconds = props.feedCacheTtlSeconds();
        this.meters = meters;
    }

    static String key(String cursor, int limit) {
        return "feed:" + (cursor == null ? "first" : cursor) + ":" + limit;
    }

    public Optional<FeedPage> get(String cursor, int limit) {
        try {
            String raw = breaker.call(() -> redis.opsForValue().get(key(cursor, limit)));
            count(raw == null ? "miss" : "hit");
            return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, FeedPage.class));
        } catch (DataAccessException e) {
            count("unavailable");
            return Optional.empty();
        } catch (JsonProcessingException e) {
            count("miss");
            return Optional.empty();
        }
    }

    public void put(String cursor, int limit, FeedPage page) {
        int jitter = ThreadLocalRandom.current().nextInt(0, Math.max(1, ttlSeconds / 5) + 1);
        try {
            String raw = json.writeValueAsString(page);
            breaker.run(() -> redis.opsForValue().set(key(cursor, limit), raw, Duration.ofSeconds(ttlSeconds + jitter)));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // Caching is best effort: the response is already correct.
        }
    }

    /**
     * Drops the cached first pages (every page size up to {@code maxLimit}), so a newly
     * published clip shows at the top at once. Plain DELETEs by key: no KEYS scan.
     */
    public void evictFirstPages(int maxLimit) {
        List<String> keys = new ArrayList<>(maxLimit);
        for (int limit = 1; limit <= maxLimit; limit++) keys.add(key(null, limit));
        try {
            breaker.call(() -> redis.delete(keys));
        } catch (DataAccessException ignored) {
            // Redis down: the pages expire on their own within the TTL.
        }
    }

    private void count(String result) {
        meters.counter("glimpse.feed.cache", "result", result).increment();
    }
}
