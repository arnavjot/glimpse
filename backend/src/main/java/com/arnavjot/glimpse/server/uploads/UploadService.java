package com.arnavjot.glimpse.server.uploads;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.auth.AuthUser;
import com.arnavjot.glimpse.server.common.GlimpseProperties;
import com.arnavjot.glimpse.server.common.RateLimitProperties;
import com.arnavjot.glimpse.server.common.RateLimiter;
import com.arnavjot.glimpse.server.feed.FeedCache;
import com.arnavjot.glimpse.server.config.RemoteConfigService;

/**
 * The API side of uploads: create one (and hand out a pre-signed URL), confirm the file
 * arrived, and list a creator's uploads. Every status change is a guarded UPDATE
 * ({@code WHERE status = <expected>}), so retries and duplicate requests are harmless.
 */
@Service
public class UploadService {

    /** One upload as the creator sees it. */
    public record Upload(UUID id, String title, String status, long sizeBytes, String error, String videoId,
                         Instant createdAt) {}

    /** Where and how to PUT the file. */
    public record Ticket(Upload upload, String uploadUrl, String contentType, Instant expiresAt) {}

    static final Set<String> CONTENT_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm", "video/3gpp");

    private static final RowMapper<Upload> ROW = (rs, i) -> new Upload(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"), rs.getLong("size_bytes"),
            rs.getString("error"), rs.getString("video_id"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final UploadProperties props;
    private final RemoteConfigService config;
    private final FeedCache feedCache;
    private final int feedPageMax;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;
    private final TransactionTemplate tx;

    public UploadService(JdbcTemplate jdbc, ObjectStorage storage, UploadProperties props, RemoteConfigService config,
                         FeedCache feedCache, GlimpseProperties glimpse, RateLimiter limiter,
                         RateLimitProperties limits, TransactionTemplate tx) {
        this.limiter = limiter;
        this.limits = limits;
        this.tx = tx;
        this.jdbc = jdbc;
        this.storage = storage;
        this.props = props;
        this.config = config;
        this.feedCache = feedCache;
        this.feedPageMax = glimpse.feedPageMax();
    }

    public Ticket create(AuthUser user, String title, String contentType, long sizeBytes, String deviceId) {
        if (!config.isOn("uploads", deviceId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "uploads are paused, try again later");
        }
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "that file type isn't supported");
        }
        if (sizeBytes <= 0 || sizeBytes > props.maxBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "videos can be up to " + props.maxBytes() / (1024 * 1024) + " MB");
        }
        UUID id = UUID.randomUUID();
        String key = "originals/" + user.id() + "/" + id;
        // Uploads are the most expensive thing the API does (storage, then three ffmpeg
        // encodes), so each account gets a cap on uploads in progress and an hourly limit.
        tx.executeWithoutResult(status -> {
            // One create at a time per account, so two parallel requests can't both pass the cap.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                    "uploads:" + user.id());
            Integer busy = jdbc.queryForObject("""
                    SELECT count(*) FROM uploads
                     WHERE user_id = ?
                       AND (status IN ('uploaded', 'processing')
                            -- Waiting for the file, while its upload URL still works. An expired
                            -- one can never finish, so it doesn't hold a slot until cleanup.
                            OR (status = 'pending_upload' AND created_at > now() - make_interval(mins => ?)))""",
                    Integer.class, user.id(), props.urlMinutes());
            if (busy != null && busy >= props.maxInProgress()) {
                throw new RateLimiter.LimitedException("you have " + busy + " uploads in progress, "
                        + "post more when one of them is done", 60);
            }
            // After the cap check, so an upload refused for being one too many doesn't use up the hour.
            limiter.take(limits.uploads(), user.id().toString(), "uploads");
            jdbc.update("INSERT INTO uploads (id, user_id, title, content_type, size_bytes, object_key, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, 'pending_upload')", id, user.id(), title.trim(), contentType, sizeBytes, key);
        });
        Duration ttl = Duration.ofMinutes(props.urlMinutes());
        String url = storage.presignPut(key, contentType, sizeBytes, ttl).toString();
        return new Ticket(get(user, id), url, contentType, Instant.now().plus(ttl));
    }

    /**
     * The app says the PUT finished: check the object really is in storage and is the
     * declared size, then queue it. Calling it again later just returns the current state.
     */
    public Upload complete(AuthUser user, UUID id) {
        Upload u = get(user, id);
        if (!"pending_upload".equals(u.status())) return u; // already done: idempotent
        String key = jdbc.queryForObject("SELECT object_key FROM uploads WHERE id = ?", String.class, id);
        OptionalLong size = storage.size(key);
        if (size.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the file hasn't arrived in storage yet");
        }
        if (size.getAsLong() > props.maxBytes() || size.getAsLong() != u.sizeBytes()) {
            jdbc.update("UPDATE uploads SET status = 'failed', error = ?, updated_at = now() "
                    + "WHERE id = ? AND status = 'pending_upload'", "the uploaded file doesn't match its size", id);
            return get(user, id);
        }
        jdbc.update("UPDATE uploads SET status = 'uploaded', updated_at = now() WHERE id = ? AND status = 'pending_upload'",
                id);
        return get(user, id);
    }

    /** Newest first. */
    public List<Upload> mine(AuthUser user) {
        return jdbc.query("SELECT * FROM uploads WHERE user_id = ? AND status <> 'deleted' "
                + "ORDER BY created_at DESC LIMIT 50", ROW, user.id());
    }

    /**
     * Deletes one of this user's uploads. The clip leaves the feed at once (its videos row
     * goes, with its likes); the files are removed by the cleanup job, the only process with
     * write access to the media folder. Deleting twice is fine; someone else's is a 404.
     */
    @Transactional
    public void delete(AuthUser user, UUID id) {
        List<String> videoIds = jdbc.query("SELECT video_id FROM uploads WHERE id = ? AND user_id = ? FOR UPDATE",
                (rs, i) -> rs.getString("video_id"), id, user.id());
        if (videoIds.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such upload");
        String videoId = videoIds.get(0);
        jdbc.update("UPDATE uploads SET status = 'deleted', video_id = NULL, lease_until = NULL, updated_at = now() "
                + "WHERE id = ?", id);
        if (videoId != null) {
            jdbc.update("DELETE FROM videos WHERE id = ?", videoId);
            // After commit, so a reader can't re-cache the page between eviction and commit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    feedCache.evictFirstPages(feedPageMax);
                }
            });
        }
    }

    /** One of this user's uploads; someone else's is a 404, the same as a missing one. */
    public Upload get(AuthUser user, UUID id) {
        return jdbc.query("SELECT * FROM uploads WHERE id = ? AND user_id = ? AND status <> 'deleted'", ROW, id, user.id())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such upload"));
    }
}
