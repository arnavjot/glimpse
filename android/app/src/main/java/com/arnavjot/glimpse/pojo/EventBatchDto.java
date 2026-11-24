package com.arnavjot.glimpse.pojo;

import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public final class EventBatchDto {
    @SerializedName("device_id") public final String deviceId;
    @SerializedName("events") public final List<Event> events;

    public EventBatchDto(String deviceId, List<Event> events) {
        this.deviceId = deviceId;
        this.events = events;
    }

    public static final class Event {
        @SerializedName("event_id") public String eventId;
        @SerializedName("video_id") public String videoId;
        @SerializedName("type") public String type;
        @Nullable @SerializedName("value_ms") public Long valueMs;
        @Nullable @SerializedName("preload") public Boolean preload;
        @SerializedName("session_id") public String sessionId;
        /** ISO-8601 with offset, e.g. 2026-11-21T10:00:00.123Z */
        @SerializedName("occurred_at") public String occurredAt;
    }
}
