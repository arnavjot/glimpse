package com.arnavjot.glimpse.pojo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Local copy of the measurements the Performance screen summarises (pending
 * events are deleted once uploaded, so they can't be the source).
 */
@Entity(tableName = "local_samples", indices = {@Index({"type", "preload"})})
public class LocalSample {
    public static final String FIRST_FRAME = "first_frame";
    public static final String PLAY_START = "play_start";
    public static final String REBUFFER = "rebuffer";

    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String type = "";
    public long valueMs;
    public boolean preload;
    public long at;
}
