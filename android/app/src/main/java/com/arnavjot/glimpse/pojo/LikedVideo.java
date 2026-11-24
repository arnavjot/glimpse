package com.arnavjot.glimpse.pojo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * A clip this device has liked. Kept apart from {@link VideoEntity} because the feed
 * table is wiped on every refresh, and the server's feed doesn't say what this device
 * liked: without this table a like on a clip beyond the first page was forgotten.
 */
@Entity(tableName = "liked_videos")
public class LikedVideo {
    @PrimaryKey @NonNull public String id = "";

    public LikedVideo() {
    }

    @Ignore // for code; Room uses the no-arg constructor
    public LikedVideo(@NonNull String id) {
        this.id = id;
    }
}
