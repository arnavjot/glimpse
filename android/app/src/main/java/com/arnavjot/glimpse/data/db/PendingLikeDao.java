package com.arnavjot.glimpse.data.db;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.arnavjot.glimpse.pojo.PendingLike;

import java.util.List;

@Dao
public interface PendingLikeDao {

    @Nullable
    @Query("SELECT * FROM pending_likes WHERE videoId = :videoId")
    PendingLike get(String videoId);

    /** The next one to send: the longest waiting. */
    @Nullable
    @Query("SELECT * FROM pending_likes ORDER BY queuedAt LIMIT 1")
    PendingLike oldest();

    @Query("SELECT * FROM pending_likes")
    List<PendingLike> all();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(PendingLike like);

    /** Compare-and-delete: removes the row only if no newer tap replaced it. @return rows deleted (0 or 1) */
    @Query("DELETE FROM pending_likes WHERE videoId = :videoId AND version = :version")
    int deleteIfUnchanged(String videoId, long version);
}
