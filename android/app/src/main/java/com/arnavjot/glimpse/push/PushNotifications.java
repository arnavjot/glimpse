package com.arnavjot.glimpse.push;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.arnavjot.glimpse.R;
import com.arnavjot.glimpse.common.Links;
import com.arnavjot.glimpse.ui.MainActivity;
import com.bumptech.glide.Glide;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.TimeUnit;

/**
 * "New clip" notifications over Firebase Cloud Messaging.
 *
 * <p>Opt-in (Settings): turning it on subscribes this install to the server's
 * "new-clips" topic. Messages are data-only, so the app builds every notification and
 * tapping one opens the clip at the top of the feed through the deep link.
 *
 * <p>Without google-services.json the build has no Firebase project: {@link #available}
 * is false and everything here is a no-op.
 */
public final class PushNotifications {

    public static final String TOPIC_NEW_CLIPS = "new-clips";
    static final String CHANNEL_NEW_CLIPS = "new_clips";
    private static final String TAG = "PushNotifications";
    private static final long POSTER_TIMEOUT_S = 5;

    private PushNotifications() {
    }

    /** True if this build was configured with a Firebase project. */
    public static boolean available(@NonNull Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }

    /** Safe to call on every start: creating an existing channel is a no-op. */
    public static void createChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_NEW_CLIPS,
                context.getString(R.string.channel_new_clips), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.channel_new_clips_description));
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    /** Subscribes to or leaves the new-clips topic (the Settings switch). */
    public static void setSubscribed(@NonNull Context context, boolean on) {
        if (!available(context)) return;
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        (on ? messaging.subscribeToTopic(TOPIC_NEW_CLIPS) : messaging.unsubscribeFromTopic(TOPIC_NEW_CLIPS))
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) Log.w(TAG, "topic change failed; FCM retries", task.getException());
                });
    }

    /** Android 13+ needs the runtime permission; earlier versions only the app-level switch. */
    public static boolean canPost(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /** Posts the notification; called on FCM's background thread, so the poster can load. */
    @WorkerThread
    static void show(@NonNull Context context, @NonNull NewClipMessage message) {
        if (!canPost(context)) return;
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(Links.deepLink(message.videoId)))
                .setClass(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = message.videoId.hashCode();
        PendingIntent tap = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_NEW_CLIPS)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.pn_light_brand))
                .setContentTitle(context.getString(R.string.notification_new_clip_title, message.creator))
                .setContentText(message.title)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
        Bitmap poster = loadPoster(context, message.posterUrl);
        if (poster != null) {
            builder.setLargeIcon(poster)
                    .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(poster).bigLargeIcon((Bitmap) null));
        }
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "notification permission revoked", e); // between canPost() and notify()
        }
    }

    @Nullable
    private static Bitmap loadPoster(@NonNull Context context, @Nullable String url) {
        if (url == null) return null;
        try {
            return Glide.with(context).asBitmap().load(url).submit().get(POSTER_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) { // ExecutionException, TimeoutException: post it without a picture
            return null;
        }
    }
}
