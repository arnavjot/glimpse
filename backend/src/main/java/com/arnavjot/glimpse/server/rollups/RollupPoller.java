package com.arnavjot.glimpse.server.rollups;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.arnavjot.glimpse.server.ranking.RankingJob;

/**
 * Runs in the worker container, every {@code glimpse.ranking.refresh-millis} (5 minutes):
 * fold new events into the hourly rollups, then build a fresh "For You" snapshot from them.
 * Safe with several workers: the rollup locks its bookmark, the ranking takes a lock.
 */
@Component
@ConditionalOnProperty(name = "glimpse.worker.enabled", havingValue = "true")
public class RollupPoller {

    private static final Logger log = LoggerFactory.getLogger(RollupPoller.class);

    private final RollupJob rollups;
    private final RankingJob ranking;

    public RollupPoller(RollupJob rollups, RankingJob ranking) {
        this.rollups = rollups;
        this.ranking = ranking;
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${glimpse.ranking.refresh-millis:300000}")
    void refresh() {
        try {
            rollups.runOnce();
            ranking.runOnce();
        } catch (RuntimeException e) {
            log.warn("Rollup / ranking refresh failed; retrying next time", e);
        }
    }
}
