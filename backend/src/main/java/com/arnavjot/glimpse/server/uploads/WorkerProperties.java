package com.arnavjot.glimpse.server.uploads;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled      run the polling worker in this process
 * @param mediaDir     where finished HLS is written (nginx serves it)
 * @param pollMillis   delay between queue checks when idle
 * @param leaseMinutes a claimed job not finished in this time is taken over
 * @param maxAttempts  attempts before an upload is marked failed
 */
@ConfigurationProperties("glimpse.worker")
public record WorkerProperties(boolean enabled, String mediaDir, long pollMillis, int leaseMinutes, int maxAttempts) {}
