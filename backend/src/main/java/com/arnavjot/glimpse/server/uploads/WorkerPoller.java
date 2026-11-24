package com.arnavjot.glimpse.server.uploads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the upload worker on a schedule, only in processes started with WORKER_ENABLED=true
 * (the "worker" container). API containers never transcode. Drains the queue, then waits;
 * every 5 minutes it also cleans up leftovers.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "glimpse.worker.enabled", havingValue = "true")
public class WorkerPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoller.class);
    private static final int MAX_JOBS_PER_TICK = 20;

    private final UploadWorker worker;
    private final UploadCleaner cleaner;

    public WorkerPoller(UploadWorker worker, UploadCleaner cleaner) {
        this.worker = worker;
        this.cleaner = cleaner;
        log.info("Upload worker is running");
    }

    @Scheduled(fixedDelayString = "${glimpse.worker.poll-millis:2000}")
    void poll() {
        try {
            for (int i = 0; i < MAX_JOBS_PER_TICK && worker.runOnce(); i++) {
                // keep going while there is work
            }
        } catch (RuntimeException e) {
            log.warn("Upload worker tick failed (database down?); retrying next tick", e);
        }
    }

    /** Every 5 minutes (a deleted clip's files go soon after): abandoned, old failed, deleted uploads. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    void cleanUp() {
        try {
            cleaner.runOnce();
        } catch (RuntimeException e) {
            log.warn("Upload cleanup failed; retrying in 5 minutes", e);
        }
    }
}
