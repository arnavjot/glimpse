package com.arnavjot.glimpse.server.rollups;

import java.sql.Timestamp;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Folds new playback events into hourly totals per clip (clip_stats_hourly).
 *
 * <ul>
 *   <li><b>Incremental</b>: reads only events received since its bookmark
 *       (rollup_state.processed_upto), so the cost follows new traffic, not history.</li>
 *   <li><b>Exactly once</b>: the totals and the bookmark change in one transaction. A crash
 *       before commit leaves both as they were; the next run redoes the same window.</li>
 *   <li><b>One at a time</b>: the bookmark row is locked (FOR UPDATE), so two workers can't
 *       fold the same events.</li>
 *   <li><b>Late commits</b>: received_at is set when an insert starts, and a slow insert can
 *       commit after a later one. Only events at least {@link #SETTLE} old are read, so one
 *       can't commit behind the bookmark.</li>
 * </ul>
 */
@Component
public class RollupJob {

    static final String NAME = "clip_stats_hourly";
    static final Duration SETTLE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(RollupJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public RollupJob(JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.meters = meters;
    }

    /** One pass. @return how many hourly rows were created or updated */
    public int runOnce() {
        return runOnce(SETTLE);
    }

    /** One pass, reading events older than {@code settle} (tests use zero). */
    public int runOnce(Duration settle) {
        Integer rows = tx.execute(status -> {
            Timestamp from = jdbc.queryForObject(
                    "SELECT processed_upto FROM rollup_state WHERE name = ? FOR UPDATE", Timestamp.class, NAME);
            Timestamp upto = jdbc.queryForObject("SELECT clock_timestamp() - make_interval(secs => ?)",
                    Timestamp.class, settle.toSeconds());
            if (from != null && !upto.after(from)) return 0;
            int changed = jdbc.update("""
                    INSERT INTO clip_stats_hourly
                           (video_id, hour, plays, completions, watch_ms, first_frames, first_frame_ms, rebuffers)
                    SELECT video_id,
                           date_trunc('hour', occurred_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC',
                           count(*) FILTER (WHERE type = 'play_start'),
                           count(*) FILTER (WHERE type = 'complete'),
                           coalesce(sum(value_ms) FILTER (WHERE type = 'watch'), 0),
                           count(*) FILTER (WHERE type = 'first_frame'),
                           coalesce(sum(value_ms) FILTER (WHERE type = 'first_frame'), 0),
                           count(*) FILTER (WHERE type = 'rebuffer')
                      FROM playback_events
                     WHERE received_at > ? AND received_at <= ?
                       AND type IN ('play_start', 'complete', 'watch', 'first_frame', 'rebuffer')
                     GROUP BY 1, 2
                    ON CONFLICT (video_id, hour) DO UPDATE SET
                           plays          = clip_stats_hourly.plays          + EXCLUDED.plays,
                           completions    = clip_stats_hourly.completions    + EXCLUDED.completions,
                           watch_ms       = clip_stats_hourly.watch_ms       + EXCLUDED.watch_ms,
                           first_frames   = clip_stats_hourly.first_frames   + EXCLUDED.first_frames,
                           first_frame_ms = clip_stats_hourly.first_frame_ms + EXCLUDED.first_frame_ms,
                           rebuffers      = clip_stats_hourly.rebuffers      + EXCLUDED.rebuffers""",
                    from, upto);
            jdbc.update("UPDATE rollup_state SET processed_upto = ? WHERE name = ?", upto, NAME);
            return changed;
        });
        int n = rows == null ? 0 : rows;
        if (n > 0) {
            meters.counter("glimpse.rollups.rows").increment(n);
            log.info("Rolled up events into {} hourly rows", n);
        }
        return n;
    }
}
