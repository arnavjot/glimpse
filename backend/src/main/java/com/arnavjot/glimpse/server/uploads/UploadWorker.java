package com.arnavjot.glimpse.server.uploads;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.arnavjot.glimpse.server.common.GlimpseProperties;
import com.arnavjot.glimpse.server.feed.FeedCache;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Processes uploads: uploaded → processing → ready / failed.
 *
 * <ul>
 *   <li><b>Queue</b>: the uploads table. A worker claims the oldest waiting row with
 *       FOR UPDATE SKIP LOCKED, so any number of workers share the queue and never take
 *       the same job.</li>
 *   <li><b>Lease</b>: a claimed job holds a lease. If the worker dies, the lease runs out
 *       and another worker takes the job over. After {@code maxAttempts} it fails.</li>
 *   <li><b>Idempotent</b>: output goes to a staging folder and is moved into place in one
 *       rename; the video row is inserted ON CONFLICT DO NOTHING; the final status change
 *       only applies if this worker still holds the job. Running a job twice is harmless.</li>
 * </ul>
 */
@Component
public class UploadWorker {

    record Job(UUID id, String objectKey, String title, String handle, int attempts) {}

    private static final Logger log = LoggerFactory.getLogger(UploadWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectStorage storage;
    private final Transcoder transcoder;
    private final WorkerProperties props;
    private final UploadProperties uploads;
    private final FeedCache feedCache;
    private final int feedPageMax;
    private final MeterRegistry meters;

    public UploadWorker(JdbcTemplate jdbc, TransactionTemplate tx, ObjectStorage storage, Transcoder transcoder,
                        WorkerProperties props, UploadProperties uploads, FeedCache feedCache,
                        GlimpseProperties glimpse, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.storage = storage;
        this.transcoder = transcoder;
        this.props = props;
        this.uploads = uploads;
        this.feedCache = feedCache;
        this.feedPageMax = glimpse.feedPageMax();
        this.meters = meters;
    }

    /** Takes and processes one job. @return false if the queue was empty */
    public boolean runOnce() {
        Optional<Job> claimed = claim();
        if (claimed.isEmpty()) return false;
        Job job = claimed.get();
        if (job.attempts() > props.maxAttempts()) {
            finishFailed(job, "processing kept failing, try another video");
            return true;
        }
        long started = System.nanoTime();
        Path work = null;
        Path staging = null;
        try {
            work = Files.createTempDirectory("upload-");
            Path original = work.resolve("original");
            storage.download(job.objectKey(), original);

            String videoId = videoId(job.id());
            Path mediaDir = Path.of(props.mediaDir());
            // One folder per attempt: if our lease runs out and another worker takes the job
            // over, the two never write into the same place.
            staging = mediaDir.resolve(".staging-" + videoId + "-" + job.attempts());
            deleteRecursively(staging);
            Files.createDirectories(staging);
            long durationMs = transcoder.transcode(original, staging, uploads.maxSeconds());

            Path finished = staging;
            boolean published = Boolean.TRUE.equals(tx.execute(status -> publish(job, videoId, durationMs, finished)));
            if (!published) {
                // Deleted or taken over meanwhile: our output is dropped (finally block).
                log.info("Upload {} attempt {} is no longer ours; output discarded", job.id(), job.attempts());
            } else {
                staging = null; // moved into place
                feedCache.evictFirstPages(feedPageMax); // show the new clip now, not after the TTL
                meters.counter("glimpse.uploads.processed", "result", "ready").increment();
                log.info("Upload {} is ready as {} ({} ms to process)", job.id(), videoId,
                        (System.nanoTime() - started) / 1_000_000);
            }
        } catch (Transcoder.TranscodeException e) {
            log.warn("Upload {} attempt {} failed: {}", job.id(), job.attempts(), e.getMessage());
            // A file that isn't a playable video won't improve on retry.
            finishFailed(job, e.userMessage);
        } catch (IOException | RuntimeException e) {
            log.warn("Upload {} attempt {} failed, will retry", job.id(), job.attempts(), e);
            retryLater(job, "temporary problem, retrying");
        } finally {
            if (staging != null) deleteQuietly(staging);
            if (work != null) deleteQuietly(work);
        }
        return true;
    }

    /** Claims the oldest waiting job, or one whose worker's lease ran out. */
    Optional<Job> claim() {
        List<Job> jobs = jdbc.query("""
                UPDATE uploads u
                   SET status = 'processing', attempts = u.attempts + 1, updated_at = now(),
                       lease_until = now() + make_interval(mins => ?)
                  FROM users
                 WHERE users.id = u.user_id
                   AND u.id = (SELECT id FROM uploads
                                WHERE status = 'uploaded'
                                   OR (status = 'processing' AND lease_until < now())
                                ORDER BY updated_at
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED)
                RETURNING u.id, u.object_key, u.title, users.handle, u.attempts""",
                (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getString("object_key"),
                        rs.getString("title"), rs.getString("handle"), rs.getInt("attempts")),
                props.leaseMinutes());
        return jobs.stream().findFirst();
    }

    /**
     * The files go into place, the clip into the feed and the upload becomes ready, or
     * nothing happens. The upload row is locked first and must still be this attempt's
     * job: if it was deleted or taken over meanwhile, the files are never moved (the caller
     * deletes them), so no worker can leave files that nothing would ever clean up.
     */
    private boolean publish(Job job, String videoId, long durationMs, Path staging) {
        List<UUID> ours = jdbc.query("SELECT id FROM uploads WHERE id = ? AND status = 'processing' AND attempts = ? "
                + "FOR UPDATE", (rs, i) -> rs.getObject("id", UUID.class), job.id(), job.attempts());
        if (ours.isEmpty()) return false;
        // Same filesystem as the final folder: the rename is atomic, so nginx never serves a
        // half-written clip. A folder from an earlier, abandoned attempt is replaced.
        Path target = Path.of(props.mediaDir()).resolve(videoId);
        try {
            deleteRecursively(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // rolls back; the job is retried
        }
        jdbc.update("""
                INSERT INTO videos (id, title, creator, duration_ms, width, height, hls_path, poster_path, published_at)
                VALUES (?, ?, ?, ?, 720, 1280, ?, ?, ?) ON CONFLICT (id) DO NOTHING""",
                videoId, job.title(), job.handle(), durationMs, videoId + "/index.m3u8", videoId + "/poster.jpg",
                Timestamp.from(Instant.now()));
        jdbc.update("""
                UPDATE uploads SET status = 'ready', video_id = ?, error = NULL, lease_until = NULL, updated_at = now()
                 WHERE id = ?""", videoId, job.id());
        return true;
    }

    private void finishFailed(Job job, String message) {
        jdbc.update("UPDATE uploads SET status = 'failed', error = ?, lease_until = NULL, updated_at = now() "
                + "WHERE id = ? AND status = 'processing' AND attempts = ?", message, job.id(), job.attempts());
        meters.counter("glimpse.uploads.processed", "result", "failed").increment();
    }

    private void retryLater(Job job, String message) {
        if (job.attempts() >= props.maxAttempts()) {
            finishFailed(job, "processing failed, try again later");
            return;
        }
        jdbc.update("UPDATE uploads SET status = 'uploaded', error = ?, lease_until = NULL, updated_at = now() "
                + "WHERE id = ? AND status = 'processing' AND attempts = ?", message, job.id(), job.attempts());
    }

    /** Feed id for an upload: "u" + 12 hex digits of its id (fits the API's id rules). */
    public static String videoId(UUID uploadId) {
        return "u" + uploadId.toString().replace("-", "").substring(0, 12);
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("Couldn't delete {}", dir, e);
        }
    }
}
