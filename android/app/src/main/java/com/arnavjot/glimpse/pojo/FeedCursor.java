package com.arnavjot.glimpse.pojo;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Single row: the cursor for the next feed page (null when the feed is exhausted). */
@Entity(tableName = "feed_cursor")
public class FeedCursor {
    @PrimaryKey public int id = 0;
    @Nullable public String nextCursor;
}
