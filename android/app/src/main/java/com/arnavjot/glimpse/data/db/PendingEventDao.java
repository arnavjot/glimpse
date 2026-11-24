package com.arnavjot.glimpse.data.db;

import com.arnavjot.glimpse.pojo.PendingEvent;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PendingEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(PendingEvent event);

    @Query("SELECT * FROM pending_events ORDER BY occurredAt LIMIT :limit")
    List<PendingEvent> oldest(int limit);

    @Query("DELETE FROM pending_events WHERE eventId IN (:ids)")
    void delete(List<String> ids);

    @Query("SELECT COUNT(*) FROM pending_events")
    int count();

    @Query("SELECT COUNT(*) FROM pending_events")
    LiveData<Integer> observeCount();

    /** Keep the queue bounded if the device stays offline for a very long time. */
    @Query("DELETE FROM pending_events WHERE eventId IN "
            + "(SELECT eventId FROM pending_events ORDER BY occurredAt LIMIT :n)")
    void dropOldest(int n);
}
