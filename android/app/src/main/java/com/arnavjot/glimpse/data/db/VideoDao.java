package com.arnavjot.glimpse.data.db;

import com.arnavjot.glimpse.pojo.FeedCursor;
import com.arnavjot.glimpse.pojo.LikedVideo;
import com.arnavjot.glimpse.pojo.VideoEntity;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public abstract class VideoDao {

    @Query("SELECT * FROM videos ORDER BY position")
    public abstract LiveData<List<VideoEntity>> observeFeed();

    @Query("SELECT * FROM videos ORDER BY position")
    public abstract List<VideoEntity> feed();

    /** Every clip this device has liked, including ones not in the cached feed. */
    @Query("SELECT id FROM liked_videos")
    public abstract List<String> likedIds();

    @Query("SELECT COALESCE(MAX(position), -1) FROM videos")
    public abstract int maxPosition();

    @Nullable
    @Query("SELECT * FROM videos WHERE id = :id")
    public abstract VideoEntity get(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void insertAll(List<VideoEntity> videos);

    @Query("DELETE FROM videos")
    public abstract void clear();

    /** A clip removed on the server (e.g. its creator deleted it). */
    @Query("DELETE FROM videos WHERE id = :id")
    public abstract void deleteById(String id);

    /** Sponsored clips are for online sessions only. */
    @Query("DELETE FROM videos WHERE sponsored = 1")
    public abstract void deleteSponsored();

    @Query("UPDATE videos SET liked = :liked, likeCount = :likeCount WHERE id = :id")
    protected abstract void updateLike(String id, boolean liked, int likeCount);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract void insertLiked(LikedVideo liked);

    @Query("DELETE FROM liked_videos WHERE id = :id")
    protected abstract void deleteLiked(String id);

    /** Updates the cached clip and the device's liked set together. */
    @Transaction
    public void setLike(String id, boolean liked, int likeCount) {
        updateLike(id, liked, likeCount);
        if (liked) insertLiked(new LikedVideo(id));
        else deleteLiked(id);
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public abstract void setCursor(FeedCursor cursor);

    @Query("SELECT * FROM feed_cursor WHERE id = 0")
    public abstract FeedCursor cursor();

    /** Swap in a fresh first page atomically, so the UI never sees an empty feed. */
    @Transaction
    public void replaceFeed(List<VideoEntity> firstPage, String nextCursor) {
        clear();
        insertAll(firstPage);
        FeedCursor c = new FeedCursor();
        c.nextCursor = nextCursor;
        setCursor(c);
    }

    @Transaction
    public void appendPage(List<VideoEntity> page, String nextCursor) {
        insertAll(page);
        FeedCursor c = new FeedCursor();
        c.nextCursor = nextCursor;
        setCursor(c);
    }
}
