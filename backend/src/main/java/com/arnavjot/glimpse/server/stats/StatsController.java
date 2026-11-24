package com.arnavjot.glimpse.server.stats;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Playback stats from the stored events. Percentiles use Postgres's
 * percentile_disc, which is nearest-rank: always a value that was really
 * measured, the same definition the app's Performance screen uses.
 */
@RestController
public class StatsController {

    public record Percentiles(long count, Long p50Ms, Long p95Ms) {}

    public record PlaybackStats(
            Percentiles firstFramePreloaded,
            Percentiles firstFrameNotPreloaded,
            Double rebuffersPerPlay,
            long plays) {}

    public record VideoStats(
            String videoId, long plays, long completes, Double completionRate,
            Percentiles firstFrame, long rebuffers) {}

    private static final String FIRST_FRAME_PERCENTILES = "SELECT count(*), "
            + "percentile_disc(0.5) WITHIN GROUP (ORDER BY value_ms), "
            + "percentile_disc(0.95) WITHIN GROUP (ORDER BY value_ms) "
            + "FROM playback_events WHERE type = 'first_frame' AND value_ms IS NOT NULL AND ";

    private final JdbcTemplate jdbc;

    public StatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/stats/playback")
    public PlaybackStats playback() {
        long plays = count("type = 'play_start'");
        long rebuffers = count("type = 'rebuffer'");
        return new PlaybackStats(
                percentiles("preload IS TRUE"),
                percentiles("preload IS FALSE"),
                plays == 0 ? null : round(rebuffers / (double) plays),
                plays);
    }

    @GetMapping("/v1/videos/{videoId}/stats")
    public VideoStats video(@PathVariable String videoId) {
        Integer exists = jdbc.query("SELECT 1 FROM videos WHERE id = ?", (rs, i) -> 1, videoId)
                .stream().findFirst().orElse(null);
        if (exists == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        long plays = count("type = 'play_start' AND video_id = ?", videoId);
        long completes = count("type = 'complete' AND video_id = ?", videoId);
        return new VideoStats(videoId, plays, completes,
                plays == 0 ? null : round(completes / (double) plays),
                percentiles("video_id = ?", videoId),
                count("type = 'rebuffer' AND video_id = ?", videoId));
    }

    private Percentiles percentiles(String where, Object... args) {
        return jdbc.queryForObject(FIRST_FRAME_PERCENTILES + where, (rs, i) -> new Percentiles(
                rs.getLong(1),
                rs.getObject(2) == null ? null : rs.getLong(2),
                rs.getObject(3) == null ? null : rs.getLong(3)), args);
    }

    private long count(String where, Object... args) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM playback_events WHERE " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
