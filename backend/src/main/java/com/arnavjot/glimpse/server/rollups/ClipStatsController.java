package com.arnavjot.glimpse.server.rollups;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.feed.VideoController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** A clip's stats hour by hour, read from the rollups (a few rows, not the event log). */
@RestController
@Validated
public class ClipStatsController {

    public record Hour(Instant hour, long plays, long completions, Double completionRate,
                       long watchMs, Long avgFirstFrameMs, long rebuffers) {}

    private final JdbcTemplate jdbc;

    public ClipStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/videos/{videoId}/stats/hourly")
    public List<Hour> hourly(@PathVariable @Pattern(regexp = VideoController.ID_PATTERN) String videoId,
                             @RequestParam(defaultValue = "48") @Min(1) @Max(24 * 30) int hours) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM videos WHERE id = ?)", Boolean.class, videoId);
        if (!Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found");
        return jdbc.query("""
                SELECT hour, plays, completions, watch_ms, first_frames, first_frame_ms, rebuffers
                  FROM clip_stats_hourly
                 WHERE video_id = ? AND hour >= date_trunc('hour', now()) - make_interval(hours => ?)
                 ORDER BY hour""", (rs, i) -> {
            long plays = rs.getLong("plays");
            long frames = rs.getLong("first_frames");
            return new Hour(rs.getTimestamp("hour").toInstant(), plays, rs.getLong("completions"),
                    plays == 0 ? null : Math.round(rs.getLong("completions") * 1000.0 / plays) / 1000.0,
                    rs.getLong("watch_ms"), frames == 0 ? null : rs.getLong("first_frame_ms") / frames,
                    rs.getLong("rebuffers"));
        }, videoId, hours - 1);
    }
}
