package com.arnavjot.glimpse.server.push;

import com.arnavjot.glimpse.server.feed.FeedPage;

/** Sends push notifications. {@link #enabled()} is false when Firebase isn't configured. */
public interface PushSender {

    /** The topic every app with notifications turned on subscribes to. */
    String TOPIC_NEW_CLIPS = "new-clips";

    boolean enabled();

    /**
     * Tells subscribers about a new clip. A data-only message: the app builds the
     * notification itself, so tapping it deep-links to the clip.
     *
     * @return the message id from FCM
     */
    String sendNewClip(FeedPage.Video video) throws PushException;

    final class PushException extends Exception {
        public PushException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
