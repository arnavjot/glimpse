package com.arnavjot.glimpse.pojo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A like or unlike the server hasn't confirmed yet. One row per clip holding the
 * latest wish, so tapping five times offline sends one request, not five.
 *
 * <p>{@link #version} goes up on every tap. The sync deletes a row only if its version
 * is unchanged since it was read, so a tap made while a request is in flight is never
 * lost or overwritten by that request's answer.
 */
@Entity(tableName = "pending_likes", indices = {@Index("queuedAt")})
public class PendingLike {
    @PrimaryKey @NonNull public String videoId = "";
    /** The state the user asked for. */
    public boolean liked;
    public long version;
    /** Epoch millis of the latest tap; the queue is sent oldest first. */
    public long queuedAt;

    public PendingLike() {
    }

    @Ignore // for code; Room uses the no-arg constructor
    public PendingLike(@NonNull String videoId, boolean liked, long version, long queuedAt) {
        this.videoId = videoId;
        this.liked = liked;
        this.version = version;
        this.queuedAt = queuedAt;
    }
}
