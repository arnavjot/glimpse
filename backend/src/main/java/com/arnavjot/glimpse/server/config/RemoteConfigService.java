package com.arnavjot.glimpse.server.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Feature flags and remote config, read from the remote_config table.
 *
 * <p>A flag is on for a device when it is enabled (the kill switch) and the device's
 * stable bucket (0-99, from a hash of flag key + device id) is below the rollout
 * percentage. Hashing the key in means each flag rolls out to a different slice of
 * devices, and the same device stays in or out as the percentage grows.
 *
 * <p>Rows are cached for {@link #CACHE_MILLIS}: every request reads memory, and a change
 * (a kill switch) reaches every server instance within that time.
 */
@Service
public class RemoteConfigService {

    public record Entry(String key, boolean enabled, int rolloutPercent, String value, String description) {}

    static final long CACHE_MILLIS = 5_000;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, Entry> cached = Map.of();
    /** When the cache was filled, or {@link #NEVER}. */
    private volatile long loadedAt = NEVER;
    private static final long NEVER = -1;

    @Autowired
    public RemoteConfigService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    RemoteConfigService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** All entries, in key order (for the admin listing). */
    public List<Entry> all() {
        return List.copyOf(entries().values());
    }

    public Optional<Entry> get(String key) {
        return Optional.ofNullable(entries().get(key));
    }

    /** Is this flag on for this device? Unknown flags are off; no device id only gets 100% flags. */
    public boolean isOn(String key, String deviceId) {
        Entry e = entries().get(key);
        if (e == null || !e.enabled()) return false;
        if (e.rolloutPercent() >= 100) return true;
        if (deviceId == null || e.rolloutPercent() <= 0) return false;
        return bucket(key, deviceId) < e.rolloutPercent();
    }

    /** A flag's value as a number, or {@code fallback} if it is missing, off or not a number. */
    public int intValue(String key, String deviceId, int fallback) {
        if (!isOn(key, deviceId)) return fallback;
        try {
            return Integer.parseInt(entries().get(key).value().trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** What one device sees: flag name -> on/off, and the values of the flags that are on. */
    public Map<String, Object> resolveFor(String deviceId) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (Entry e : entries().values()) {
            boolean on = isOn(e.key(), deviceId);
            flags.put(e.key(), on);
            if (on && e.value() != null) values.put(e.key(), e.value());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flags", flags);
        out.put("values", values);
        return out;
    }

    /** Updates the given fields (null = unchanged). @return the entry after the change */
    public Optional<Entry> update(String key, Boolean enabled, Integer rolloutPercent, String value) {
        int rows = jdbc.update("""
                UPDATE remote_config
                   SET enabled = COALESCE(?, enabled),
                       rollout_percent = COALESCE(?, rollout_percent),
                       value = COALESCE(?, value),
                       updated_at = now()
                 WHERE key = ?""", enabled, rolloutPercent, value, key);
        invalidate();
        return rows == 0 ? Optional.empty() : get(key);
    }

    /** Drops the cache, so this instance sees a change at once (others within CACHE_MILLIS). */
    public void invalidate() {
        loadedAt = NEVER;
    }

    /** 0..99, the same for a given flag and device every time. */
    public static int bucket(String key, String deviceId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((key + ":" + deviceId).getBytes(StandardCharsets.UTF_8));
            int n = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
            return Math.floorMod(n, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private Map<String, Entry> entries() {
        long now = clock.millis();
        // An explicit "never loaded" check: a MIN_VALUE sentinel would overflow in
        // now - loadedAt and make an empty cache look fresh forever.
        if (loadedAt != NEVER && now - loadedAt < CACHE_MILLIS) return cached;
        Map<String, Entry> fresh = new LinkedHashMap<>();
        jdbc.query("SELECT key, enabled, rollout_percent, value, description FROM remote_config ORDER BY key",
                rs -> {
                    fresh.put(rs.getString("key"), new Entry(rs.getString("key"), rs.getBoolean("enabled"),
                            rs.getInt("rollout_percent"), rs.getString("value"), rs.getString("description")));
                });
        cached = Collections.unmodifiableMap(fresh); // keeps key order
        loadedAt = now;
        return cached;
    }
}
