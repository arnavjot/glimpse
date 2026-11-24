package com.arnavjot.glimpse.server.uploads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Removes what uploads leave behind. Runs in the worker (the only process that may write
 * the media folder):
 * <ul>
 *   <li>abandoned: started but never completed within {@code abandonHours}</li>
 *   <li>failed: older than {@code failedDays}</li>
 *   <li>deleted by the creator</li>
 * </ul>
 * Each row is claimed with DELETE ... RETURNING, so several workers can run this at once
 * without handling the same upload twice. File removal is idempotent: if it fails, the
 * file is just left (and logged), never the database half-done.
 */
@Component
public class UploadCleaner {

    record Leftover(UUID id, String objectKey, String status) {}

    private static final Logger log = LoggerFactory.getLogger(UploadCleaner.class);
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final UploadProperties uploads;
    private final WorkerProperties worker;
    private final MeterRegistry meters;

    public UploadCleaner(JdbcTemplate jdbc, ObjectStorage storage, UploadProperties uploads,
                         WorkerProperties worker, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.uploads = uploads;
        this.worker = worker;
        this.meters = meters;
    }

    /** One pass. @return how many uploads were cleaned up */
    public int runOnce() {
        int total = 0;
        List<Leftover> batch;
        do {
            batch = claimBatch();
            for (Leftover l : batch) removeFiles(l);
            total += batch.size();
        } while (batch.size() == BATCH);
        if (total > 0) log.info("Cleaned up {} uploads", total);
        return total;
    }

    private List<Leftover> claimBatch() {
        return jdbc.query("""
                DELETE FROM uploads
                 WHERE id IN (SELECT id FROM uploads
                               WHERE status = 'deleted'
                                  OR (status = 'pending_upload' AND updated_at < now() - make_interval(hours => ?))
                                  OR (status = 'failed' AND updated_at < now() - make_interval(days => ?))
                               LIMIT ?
                               FOR UPDATE SKIP LOCKED)
                RETURNING id, object_key, status""",
                (rs, i) -> new Leftover(rs.getObject("id", UUID.class), rs.getString("object_key"),
                        rs.getString("status")),
                uploads.abandonHours(), uploads.failedDays(), BATCH);
    }

    private void removeFiles(Leftover l) {
        try {
            storage.delete(l.objectKey());
        } catch (RuntimeException e) {
            log.warn("Couldn't delete original {} of upload {}", l.objectKey(), l.id(), e);
        }
        // The HLS folder exists for clips that were processed (also if deleted mid-processing).
        Path hls = Path.of(worker.mediaDir()).resolve(UploadWorker.videoId(l.id()));
        try {
            UploadWorker.deleteRecursively(hls);
        } catch (IOException e) {
            log.warn("Couldn't delete {} of upload {}", hls, l.id(), e);
        }
        meters.counter("glimpse.uploads.cleaned", "reason", l.status()).increment();
    }
}
