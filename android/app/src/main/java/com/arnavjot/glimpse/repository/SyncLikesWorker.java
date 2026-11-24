package com.arnavjot.glimpse.repository;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.arnavjot.glimpse.GlimpseApp;

import java.util.concurrent.TimeUnit;

/**
 * Sends queued likes when there is a network, retrying with backoff. WorkManager
 * keeps the job across app restarts and reboots, so an offline like is sent once
 * the phone is back online, even if the app was closed.
 */
public class SyncLikesWorker extends Worker {

    static final String UNIQUE_NAME = "sync-likes";

    public SyncLikesWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        LikeSync sync = ((GlimpseApp) getApplicationContext()).component().likeSync();
        return sync.syncPending() == LikeSync.Outcome.DONE ? Result.success() : Result.retry();
    }

    /**
     * Schedules a sync. APPEND_OR_REPLACE, not KEEP: a like queued just as a running
     * sync finds the queue empty still gets a run after it, instead of waiting for the
     * next tap.
     */
    public static void enqueue(@NonNull Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncLikesWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }
}
