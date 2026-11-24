package com.arnavjot.glimpse.data.db;

import com.arnavjot.glimpse.pojo.LocalSample;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

@Dao
public interface LocalSampleDao {

    @Insert
    void insert(LocalSample sample);

    @Query("SELECT valueMs FROM local_samples WHERE type = :type AND preload = :preload")
    List<Long> values(String type, boolean preload);

    @Query("SELECT COUNT(*) FROM local_samples WHERE type = :type")
    int count(String type);

    @Query("DELETE FROM local_samples")
    void clear();

    @Query("DELETE FROM local_samples WHERE at < :cutoffMillis")
    void deleteOlderThan(long cutoffMillis);

    /** Keeps the newest {@code keep} rows. */
    @Query("DELETE FROM local_samples WHERE id NOT IN "
            + "(SELECT id FROM local_samples ORDER BY at DESC LIMIT :keep)")
    void keepNewest(int keep);

    /** Drops samples older than the window, then caps what is left. */
    @Transaction
    default void prune(long cutoffMillis, int keep) {
        deleteOlderThan(cutoffMillis);
        keepNewest(keep);
    }
}
