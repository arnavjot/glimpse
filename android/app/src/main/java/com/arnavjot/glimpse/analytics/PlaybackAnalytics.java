package com.arnavjot.glimpse.analytics;

import androidx.annotation.Nullable;

import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.pojo.LocalSample;
import com.arnavjot.glimpse.pojo.PendingEvent;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records playback events. Each one is written to Room first (so nothing is lost
 * offline or if the app is killed) and uploaded later in batches by
 * {@link UploadEventsWorker}.
 */
public class PlaybackAnalytics {

    public static final String PLAY_START = "play_start";
    public static final String FIRST_FRAME = "first_frame";
    public static final String REBUFFER = "rebuffer";
    public static final String WATCH = "watch";
    public static final String COMPLETE = "complete";
    public static final String ERROR = "error";
    /** A sponsored clip was shown; the server's per-device daily cap counts these. */
    public static final String AD_IMPRESSION = "ad_impression";
    /** Its "Learn more" was tapped. */
    public static final String AD_CLICK = "ad_click";

    /** Cap on queued events if the device stays offline for days. */
    static final int MAX_QUEUED = 5000;
    /** Upload as soon as this many are waiting, instead of only on app background. */
    static final int UPLOAD_THRESHOLD = 50;
    /** The Performance screen summarises the last 7 days... */
    public static final long SAMPLE_WINDOW_MS = TimeUnit.DAYS.toMillis(7);
    /** ...and at most this many samples, so the table can't grow without bound. */
    static final int MAX_SAMPLES = 5000;
    /** Prune every this many recordings rather than on each one. */
    static final int PRUNE_EVERY = 100;

    /** Starts an upload; abstracted so tests don't need WorkManager. */
    public interface UploadTrigger {
        void requestUpload();
    }

    private final AppDatabase db;
    private final Executor io;
    private final UploadTrigger uploads;
    private final String sessionId = UUID.randomUUID().toString();
    private final AtomicInteger recordsSincePrune = new AtomicInteger();
    private final Clock clock;

    /** Wall-clock source; abstracted so tests can control time. */
    public interface Clock {
        long nowMillis();
    }

    public PlaybackAnalytics(AppDatabase db, Executor io, UploadTrigger uploads) {
        this(db, io, uploads, System::currentTimeMillis);
    }

    public PlaybackAnalytics(AppDatabase db, Executor io, UploadTrigger uploads, Clock clock) {
        this.db = db;
        this.io = io;
        this.uploads = uploads;
        this.clock = clock;
    }

    public void record(String type, String videoId, @Nullable Long valueMs, @Nullable Boolean preload) {
        final long now = clock.nowMillis();
        io.execute(() -> {
            PendingEvent e = new PendingEvent();
            e.eventId = UUID.randomUUID().toString();
            e.videoId = videoId;
            e.type = type;
            e.valueMs = valueMs;
            e.preload = preload;
            e.sessionId = sessionId;
            e.occurredAt = now;
            db.events().insert(e);

            if (FIRST_FRAME.equals(type) || PLAY_START.equals(type) || REBUFFER.equals(type)) {
                LocalSample s = new LocalSample();
                s.type = type;
                s.valueMs = valueMs == null ? 0 : valueMs;
                s.preload = Boolean.TRUE.equals(preload);
                s.at = now;
                db.samples().insert(s);
            }

            if (recordsSincePrune.incrementAndGet() >= PRUNE_EVERY) {
                recordsSincePrune.set(0);
                pruneSamples();
            }

            int queued = db.events().count();
            if (queued > MAX_QUEUED) db.events().dropOldest(queued - MAX_QUEUED);
            if (queued >= UPLOAD_THRESHOLD) uploads.requestUpload();
        });
    }

    public void flush() {
        uploads.requestUpload();
    }

    /**
     * Drops timing samples older than {@link #SAMPLE_WINDOW_MS} and caps the rest at
     * {@link #MAX_SAMPLES}. Blocking: call on a background thread.
     */
    public void pruneSamples() {
        db.samples().prune(clock.nowMillis() - SAMPLE_WINDOW_MS, MAX_SAMPLES);
    }
}
