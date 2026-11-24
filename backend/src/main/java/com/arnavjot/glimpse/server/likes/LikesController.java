package com.arnavjot.glimpse.server.likes;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Likes are idempotent per device: PUT twice leaves one like, DELETE twice
 * leaves none. The counter on videos only moves when a row really changed, in
 * the same transaction, so it can't drift from the likes table.
 */
@RestController
@Validated
public class LikesController {

    public record LikeRequest(@NotBlank @Size(min = 8, max = 64) String deviceId) {}

    public record LikeResult(String videoId, boolean liked, int likeCount) {}

    private final JdbcTemplate jdbc;

    public LikesController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PutMapping("/v1/videos/{videoId}/like")
    @Transactional
    public LikeResult like(@PathVariable String videoId, @Valid @RequestBody LikeRequest body) {
        requireVideo(videoId);
        int added = jdbc.update(
                "INSERT INTO likes (video_id, device_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                videoId, body.deviceId());
        if (added == 1) jdbc.update("UPDATE videos SET like_count = like_count + 1 WHERE id = ?", videoId);
        return new LikeResult(videoId, true, requireVideo(videoId));
    }

    @DeleteMapping("/v1/videos/{videoId}/like")
    @Transactional
    public LikeResult unlike(@PathVariable String videoId,
                             @RequestParam("device_id") @Size(min = 8, max = 64) String deviceId) {
        requireVideo(videoId);
        int removed = jdbc.update("DELETE FROM likes WHERE video_id = ? AND device_id = ?", videoId, deviceId);
        if (removed == 1) jdbc.update("UPDATE videos SET like_count = like_count - 1 WHERE id = ?", videoId);
        return new LikeResult(videoId, false, requireVideo(videoId));
    }

    private int requireVideo(String videoId) {
        return jdbc.query("SELECT like_count FROM videos WHERE id = ?",
                        (rs, i) -> rs.getInt(1), videoId)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "video not found"));
    }
}
