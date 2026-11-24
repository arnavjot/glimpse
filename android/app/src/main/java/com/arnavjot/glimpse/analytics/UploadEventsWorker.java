package com.arnavjot.glimpse.analytics;

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

/** Uploads queued playback events when there's a network, retrying with backoff. */
public class UploadEventsWorker extends Worker {

    static final String UNIQUE_NAME = "upload-events";

    public UploadEventsWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        EventSender sender = ((GlimpseApp) getApplicationContext()).component().eventSender();
        return sender.sendPending() == EventSender.Outcome.DONE ? Result.success() : Result.retry();
    }

    /** One upload at a time; a request while one is queued or running is merged into it. */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UploadEventsWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request);
    }
}
