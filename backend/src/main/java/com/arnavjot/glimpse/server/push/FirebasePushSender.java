package com.arnavjot.glimpse.server.push;

import java.time.Duration;

import com.arnavjot.glimpse.server.feed.FeedPage;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;

/** FCM through the Firebase Admin SDK, to the {@link #TOPIC_NEW_CLIPS} topic. */
public class FirebasePushSender implements PushSender {

    private final FirebaseMessaging messaging;

    public FirebasePushSender(FirebaseApp app) {
        this.messaging = FirebaseMessaging.getInstance(app);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String sendNewClip(FeedPage.Video video) throws PushException {
        Message message = Message.builder()
                .setTopic(TOPIC_NEW_CLIPS)
                .putData("type", "new_clip")
                .putData("video_id", video.id())
                .putData("title", video.title())
                .putData("creator", video.creator())
                .putData("poster_url", video.posterUrl())
                .setAndroidConfig(AndroidConfig.builder()
                        // High priority wakes the app to build the notification. A day-old
                        // "new clip" is no longer news, so it expires after that.
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setTtl(Duration.ofDays(1).toMillis())
                        .setCollapseKey("new_clip")
                        .build())
                .build();
        try {
            return messaging.send(message);
        } catch (FirebaseMessagingException e) {
            throw new PushException("FCM refused the message: " + e.getMessagingErrorCode(), e);
        }
    }
}
