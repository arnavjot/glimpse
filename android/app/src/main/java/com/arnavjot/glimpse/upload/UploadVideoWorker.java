package com.arnavjot.glimpse.upload;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.arnavjot.glimpse.GlimpseApp;
import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.pojo.UploadDto;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Uploads one clip in the background. A foreground job (with a progress notification),
 * so Android doesn't stop it halfway; runs only with a network; retries with backoff.
 * The file is the app's own copy (made when the clip was picked), so it survives the app
 * being closed; it is deleted once the upload succeeds or fails for good.
 */
public class UploadVideoWorker extends Worker {

    public static final String TAG = "upload";
    /** Second tag, "title:<title>": WorkInfo has no input data, and My uploads shows the title. */
    public static final String TITLE_TAG = "title:";
    public static final String KEY_FILE = "file";
    public static final String KEY_TITLE = "title";
    public static final String KEY_MIME = "mime";
    /** Progress / output: percent sent, the upload id, or the error. */
    public static final String KEY_PERCENT = "percent";
    public static final String KEY_UPLOAD_ID = "upload_id";
    public static final String KEY_ERROR = "error";

    static final String CHANNEL = "uploads";
    private static final int MAX_ATTEMPTS = 6;
    private static final long PROGRESS_EVERY_MILLIS = 500;

    public UploadVideoWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static UUID enqueue(@NonNull Context context, @NonNull File file, @NonNull String title,
                               @NonNull String mime) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(UploadVideoWorker.class)
                .setInputData(new Data.Builder()
                        .putString(KEY_FILE, file.getAbsolutePath())
                        .putString(KEY_TITLE, title)
                        .putString(KEY_MIME, mime)
                        .build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .addTag(TITLE_TAG + title)
                .build();
        WorkManager.getInstance(context).enqueue(request);
        return request.getId();
    }

    @NonNull
    @Override
    public Result doWork() {
        String path = getInputData().getString(KEY_FILE);
        String title = getInputData().getString(KEY_TITLE);
        String mime = getInputData().getString(KEY_MIME);
        if (path == null || title == null || mime == null) return Result.failure();
        File file = new File(path);
        if (!file.isFile()) return fail(null, getApplicationContext().getString(R.string.upload_file_gone));

        setForegroundAsync(foregroundInfo(title, 0));
        Prefs state = new Prefs(getApplicationContext(), getId());
        UploadClient client = ((GlimpseApp) getApplicationContext()).component().uploadClient();
        long[] lastReport = {0};
        try {
            UploadDto.Upload done = client.upload(file, title, mime, state, (sent, total) -> {
                long now = SystemClock.elapsedRealtime();
                if (now - lastReport[0] < PROGRESS_EVERY_MILLIS && sent < total) return;
                lastReport[0] = now;
                int percent = total == 0 ? 0 : (int) (sent * 100 / total);
                setProgressAsync(new Data.Builder().putInt(KEY_PERCENT, percent).putString(KEY_TITLE, title).build());
                setForegroundAsync(foregroundInfo(title, percent));
            });
            finish(file, state);
            return Result.success(new Data.Builder().putString(KEY_UPLOAD_ID, done.id).build());
        } catch (UploadClient.PermanentFailure e) {
            return fail(file, e.getMessage());
        } catch (IOException e) {
            if (getRunAttemptCount() + 1 >= MAX_ATTEMPTS) {
                return fail(file, getApplicationContext().getString(R.string.upload_gave_up));
            }
            return Result.retry(); // the id and URL are kept: the retry continues this upload
        }
    }

    /**
     * Cancelled from My uploads: delete the app's copy of the video now. (Stopped for other
     * reasons, e.g. the network dropped, the job runs again later and still needs it.)
     * The stop reason exists from Android 12; before that a cancel can't be told apart, so
     * the copy is left for UploadViewModel's 7-day prune.
     */
    @Override
    public void onStopped() {
        super.onStopped();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (getStopReason() != WorkInfo.STOP_REASON_CANCELLED_BY_APP) return;
        String path = getInputData().getString(KEY_FILE);
        if (path != null) finish(new File(path), new Prefs(getApplicationContext(), getId()));
    }

    private Result fail(@Nullable File file, @NonNull String message) {
        if (file != null) finish(file, new Prefs(getApplicationContext(), getId()));
        return Result.failure(new Data.Builder().putString(KEY_ERROR, message).build());
    }

    private static void finish(@NonNull File file, @NonNull Prefs state) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        state.clear();
    }

    @NonNull
    private ForegroundInfo foregroundInfo(@NonNull String title, int percent) {
        Context context = getApplicationContext();
        createChannel(context);
        Notification n = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.upload_notification_title))
                .setContentText(title)
                .setProgress(100, percent, percent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
        int id = getId().hashCode();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? new ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                : new ForegroundInfo(id, n);
    }

    private static void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                context.getString(R.string.upload_channel), NotificationManager.IMPORTANCE_LOW);
        context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /** {@link UploadClient.JobState} in SharedPreferences, one entry per job. */
    static final class Prefs implements UploadClient.JobState {
        private final SharedPreferences prefs;
        private final String key;

        Prefs(@NonNull Context context, @NonNull UUID workId) {
            this.prefs = context.getSharedPreferences("upload_jobs", Context.MODE_PRIVATE);
            this.key = workId.toString();
        }

        @Nullable
        @Override
        public String uploadId() {
            return prefs.getString(key + ".id", null);
        }

        @Nullable
        @Override
        public String uploadUrl() {
            return prefs.getString(key + ".url", null);
        }

        @Override
        public long urlExpiresAtMillis() {
            return prefs.getLong(key + ".expires", 0);
        }

        @Override
        public void save(@NonNull String uploadId, @NonNull String uploadUrl, long urlExpiresAtMillis) {
            prefs.edit().putString(key + ".id", uploadId).putString(key + ".url", uploadUrl)
                    .putLong(key + ".expires", urlExpiresAtMillis).commit(); // commit: must survive a crash
        }

        @Override
        public void clearUrl() {
            prefs.edit().remove(key + ".url").remove(key + ".expires").commit();
        }

        void clear() {
            prefs.edit().remove(key + ".id").remove(key + ".url").remove(key + ".expires").apply();
        }
    }
}
