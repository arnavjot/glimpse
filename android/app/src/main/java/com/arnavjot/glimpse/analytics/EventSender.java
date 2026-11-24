package com.arnavjot.glimpse.analytics;

import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.EventBatchDto;
import com.arnavjot.glimpse.pojo.EventBatchResultDto;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.pojo.PendingEvent;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import retrofit2.Response;

/**
 * Drains the pending-event queue in batches.
 *
 * <p>Delete only after a 2xx: if the response is lost, the batch is sent again
 * and the server skips the event ids it already has. A 4xx means the batch
 * itself is bad and would fail forever, so it is dropped rather than blocking
 * the queue; network errors, 5xx, 408 and 429 are retried later.
 */
public class EventSender {

    static final int BATCH_SIZE = 100;

    public enum Outcome { DONE, RETRY }

    private final ApiInterface api;
    private final AppDatabase db;
    private final Prefs prefs;

    public int lastAccepted;
    public int lastDuplicates;
    public int lastDropped;

    public EventSender(ApiInterface api, AppDatabase db, Prefs prefs) {
        this.api = api;
        this.db = db;
        this.prefs = prefs;
    }

    public Outcome sendPending() {
        lastAccepted = 0;
        lastDuplicates = 0;
        lastDropped = 0;
        while (true) {
            List<PendingEvent> batch = db.events().oldest(BATCH_SIZE);
            if (batch.isEmpty()) return Outcome.DONE;
            List<String> ids = new ArrayList<>(batch.size());
            for (PendingEvent e : batch) ids.add(e.eventId);
            try {
                Response<EventBatchResultDto> r =
                        api.sendEvents(new EventBatchDto(prefs.deviceId(), toDto(batch))).execute();
                int code = r.code();
                if (r.isSuccessful()) {
                    EventBatchResultDto body = r.body();
                    if (body != null) {
                        lastAccepted += body.accepted;
                        lastDuplicates += body.duplicates;
                    }
                    db.events().delete(ids);
                } else if (code >= 400 && code < 500 && code != 408 && code != 429) {
                    lastDropped += ids.size();
                    db.events().delete(ids);
                } else {
                    return Outcome.RETRY;
                }
            } catch (IOException e) {
                return Outcome.RETRY;
            }
        }
    }

    static List<EventBatchDto.Event> toDto(List<PendingEvent> batch) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        List<EventBatchDto.Event> out = new ArrayList<>(batch.size());
        for (PendingEvent p : batch) {
            EventBatchDto.Event e = new EventBatchDto.Event();
            e.eventId = p.eventId;
            e.videoId = p.videoId;
            e.type = p.type;
            e.valueMs = p.valueMs;
            e.preload = p.preload;
            e.sessionId = p.sessionId;
            e.occurredAt = iso.format(new Date(p.occurredAt));
            out.add(e);
        }
        return out;
    }
}
