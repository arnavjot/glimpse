package com.arnavjot.glimpse.server.experiments;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.config.RemoteConfigService;

/**
 * A/B experiments: which variant a device sees, and how each variant is doing.
 *
 * <ul>
 *   <li><b>Sticky</b>: the first time a device is seen, its variant is decided (a stable hash
 *       of experiment + device, below treatment_percent = treatment) and stored. Changing the
 *       split later only affects new devices, so nobody switches variant mid-experiment and
 *       the comparison stays clean.</li>
 *   <li><b>Kill switch</b>: a disabled experiment puts everyone on control at once, without
 *       recording exposures (they would only dilute the results).</li>
 *   <li><b>No app change</b>: the server picks the variant; results are measured from the
 *       playback events the app already sends, matched by device id.</li>
 * </ul>
 */
@Service
public class ExperimentService {

    public static final String CONTROL = "control";
    public static final String TREATMENT = "treatment";

    public record Experiment(String key, boolean enabled, int treatmentPercent, String description) {}

    private record Cached(Experiment experiment, long loadedAt) {}

    private static final long CACHE_MILLIS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ExperimentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.clock = Clock.systemUTC();
    }

    /** The device's variant: control when there is no device id, no such experiment, or it's switched off. */
    public String variant(String key, String deviceId) {
        if (deviceId == null) return CONTROL;
        Experiment e;
        try {
            e = find(key);
        } catch (RuntimeException ex) {
            log.warn("Experiment {} unavailable, serving control", key, ex);
            return CONTROL;
        }
        if (e == null || !e.enabled()) return CONTROL;
        String fresh = RemoteConfigService.bucket(key, deviceId) < e.treatmentPercent() ? TREATMENT : CONTROL;
        try {
            // Insert if new; either way read back the stored (sticky) variant.
            List<String> stored = jdbc.queryForList("""
                    WITH ins AS (
                        INSERT INTO experiment_exposures (experiment_key, device_id, variant) VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING RETURNING variant)
                    SELECT variant FROM ins
                    UNION ALL
                    SELECT variant FROM experiment_exposures WHERE experiment_key = ? AND device_id = ?""",
                    String.class, key, deviceId, fresh, key, deviceId);
            return stored.isEmpty() ? fresh : stored.get(0);
        } catch (RuntimeException ex) {
            log.warn("Couldn't record exposure to {}, serving control", key, ex);
            return CONTROL;
        }
    }

    public List<Experiment> all() {
        return jdbc.query("SELECT key, enabled, treatment_percent, description FROM experiments ORDER BY key",
                (rs, i) -> new Experiment(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4)));
    }

    public Experiment update(String key, Boolean enabled, Integer treatmentPercent) {
        int rows = jdbc.update("""
                UPDATE experiments SET enabled = coalesce(?, enabled),
                       treatment_percent = coalesce(?, treatment_percent), updated_at = now()
                 WHERE key = ?""", enabled, treatmentPercent, key);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such experiment");
        cache.remove(key);
        return find(key);
    }

    private Experiment find(String key) {
        long now = clock.millis();
        Cached c = cache.get(key);
        if (c != null && now - c.loadedAt() < CACHE_MILLIS) return c.experiment();
        List<Experiment> rows = jdbc.query(
                "SELECT key, enabled, treatment_percent, description FROM experiments WHERE key = ?",
                (rs, i) -> new Experiment(rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4)), key);
        Experiment e = rows.isEmpty() ? null : rows.get(0);
        cache.put(key, new Cached(e, now));
        return e;
    }

    /** Test hook: forget cached experiment settings. */
    public void clearCache() {
        cache.clear();
    }
}
