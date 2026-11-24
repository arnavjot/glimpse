package com.arnavjot.glimpse.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A playback event waiting to be uploaded. The id is generated here, on the
 * device, and sent to the server, which stores each id once: if an upload times
 * out after the server saved it, the retry is recognised and not double counted.
 */
@Entity(tableName = "pending_events", indices = {@Index("occurredAt")})
public class PendingEvent {
    @PrimaryKey @NonNull public String eventId = "";
    @NonNull public String videoId = "";
    @NonNull public String type = "";
    @Nullable public Long valueMs;
    @Nullable public Boolean preload;
    @NonNull public String sessionId = "";
    /** Epoch millis. */
    public long occurredAt;
}
