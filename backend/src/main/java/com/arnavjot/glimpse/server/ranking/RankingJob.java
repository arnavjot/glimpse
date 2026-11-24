package com.arnavjot.glimpse.server.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Scores every clip and freezes the order into a new numbered snapshot (ranked_clips).
 *
 * <p>Score, from the last {@link #WINDOW_DAYS} days of rollups (never the raw event log):
 * <pre>
 *   completion = (completions + K * global_completion) / (plays + K)
 *   like_rate  = (likes       + K * global_like_rate)  / (plays + K)
 *   freshness  = exp(-age_hours / 48)
 *   score      = completion + 2 * like_rate + 0.3 * freshness
 * </pre>
 * The rates are smoothed towards the site-wide average with {@link #K} imaginary plays:
 * a new clip with 1 play and 1 completion isn't "100%", and one with no plays starts at
 * the average instead of zero. Freshness gives new clips a chance to collect plays.
 *
 * <p>One snapshot at a time (transaction-level advisory lock), so two workers can't build
 * the same generation. Old snapshots are kept for {@link #KEEP_HOURS} hours so cursors
 * people are scrolling with stay valid.
 */
@Component
public class RankingJob {

    static final int WINDOW_DAYS = 7;
    static final int K = 20;
    static final int KEEP_HOURS = 6;
    private static final long LOCK = 0x676c696d7073L; // "glimps"
    private static final Logger log = LoggerFactory.getLogger(RankingJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public RankingJob(JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.meters = meters;
    }

    /** Builds a new snapshot. @return its generation, or -1 if another worker is building one */
    public long runOnce() {
        Long gen = tx.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK))) {
                return -1L;
            }
            long next = jdbc.queryForObject("SELECT coalesce(max(generation), 0) + 1 FROM ranking_snapshots", Long.class);
            jdbc.update("INSERT INTO ranking_snapshots (generation) VALUES (?)", next);
            jdbc.update("""
                    WITH stats AS (
                        SELECT video_id, sum(plays) AS plays, sum(completions) AS completions
                          FROM clip_stats_hourly
                         WHERE hour >= now() - make_interval(days => ?)
                         GROUP BY video_id),
                    recent_likes AS (
                        SELECT video_id, count(*) AS likes
                          FROM likes WHERE created_at >= now() - make_interval(days => ?)
                         GROUP BY video_id),
                    global AS (
                        SELECT coalesce(sum(s.completions)::float8 / nullif(sum(s.plays), 0), 0.5) AS completion,
                               coalesce((SELECT sum(likes) FROM recent_likes)::float8 / nullif(sum(s.plays), 0), 0.05)
                                   AS like_rate
                          FROM stats s),
                    scored AS (
                        SELECT v.id,
                               (coalesce(s.completions, 0) + ? * g.completion) / (coalesce(s.plays, 0) + ?)
                             + 2 * least((coalesce(l.likes, 0) + ? * g.like_rate) / (coalesce(s.plays, 0) + ?), 1)
                             + 0.3 * exp(-extract(epoch FROM now() - v.published_at) / 3600 / 48) AS score,
                               v.published_at
                          FROM videos v CROSS JOIN global g
                          LEFT JOIN stats s ON s.video_id = v.id
                          LEFT JOIN recent_likes l ON l.video_id = v.id)
                    INSERT INTO ranked_clips (generation, rank, video_id, score)
                    SELECT ?, row_number() OVER (ORDER BY score DESC, published_at DESC, id DESC), id, score
                      FROM scored""",
                    WINDOW_DAYS, WINDOW_DAYS, K, K, K, K, next);
            jdbc.update("DELETE FROM ranking_snapshots WHERE generation < ? AND created_at < now() - make_interval(hours => ?)",
                    next, KEEP_HOURS);
            return next;
        });
        long g = gen == null ? -1 : gen;
        if (g > 0) {
            meters.counter("glimpse.ranking.snapshots").increment();
            log.info("Ranking snapshot {} built", g);
        }
        return g;
    }
}
