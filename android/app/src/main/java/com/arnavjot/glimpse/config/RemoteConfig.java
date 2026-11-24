package com.arnavjot.glimpse.config;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.RemoteConfigDto;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import retrofit2.Response;

/**
 * Feature flags and remote values from the server (GET /v1/config).
 *
 * <ul>
 *   <li>Reads are instant and in memory: {@link #isOn(Flag)} never waits for the network.</li>
 *   <li>The last good config is kept on disk, so it holds across launches and offline.
 *       Before the first fetch each flag uses its built-in default.</li>
 *   <li>{@link #refreshIfStale()} runs on app start and on returning to the foreground,
 *       at most once per server-chosen interval; the ETag makes a re-check of unchanged
 *       config a 304 with no body. A kill switch reaches the app on its next refresh.</li>
 *   <li>A failed fetch keeps the current config: bad network never flips flags.</li>
 * </ul>
 */
public class RemoteConfig {

    /** Where the last good config is kept (SharedPreferences in the app, memory in tests). */
    public interface Store {
        @Nullable String load();

        void save(@NonNull String json);
    }

    /** Wall-clock source; abstracted so tests can move time. */
    public interface Clock {
        long nowMillis();
    }

    static final int DEFAULT_REFRESH_SECONDS = 300;
    private static final String TAG = "RemoteConfig";

    /** What is persisted: the server's answer plus the ETag and when it was fetched. */
    static final class Snapshot {
        Map<String, Boolean> flags = Collections.emptyMap();
        Map<String, String> values = Collections.emptyMap();
        int refreshSeconds = DEFAULT_REFRESH_SECONDS;
        @Nullable String etag;
        long fetchedAtMillis;
    }

    private final ApiInterface api;
    private final Store store;
    private final Clock clock;
    private final Gson gson = new Gson();
    private volatile Snapshot snapshot;

    public RemoteConfig(@NonNull ApiInterface api, @NonNull Store store, @NonNull Clock clock) {
        this.api = api;
        this.store = store;
        this.clock = clock;
        this.snapshot = restore();
    }

    public boolean isOn(@NonNull Flag flag) {
        Boolean on = snapshot.flags.get(flag.key);
        return on != null ? on : flag.defaultOn;
    }

    /** A remote value (only present while its flag is on), or null. */
    @Nullable
    public String value(@NonNull String key) {
        return snapshot.values.get(key);
    }

    /** Fetches if the config is older than the server's refresh interval. Blocking. */
    @WorkerThread
    public void refreshIfStale() {
        Snapshot s = snapshot;
        long ageMillis = clock.nowMillis() - s.fetchedAtMillis;
        if (s.fetchedAtMillis > 0 && ageMillis >= 0 && ageMillis < s.refreshSeconds * 1000L) return;
        refresh();
    }

    /** Fetches now. Blocking; on any failure the current config stays. */
    @WorkerThread
    public synchronized void refresh() {
        Snapshot current = snapshot;
        try {
            Response<RemoteConfigDto> r = api.config(current.etag).execute();
            if (r.code() == 304) {
                Snapshot same = copy(current);
                same.fetchedAtMillis = clock.nowMillis();
                commit(same);
                return;
            }
            RemoteConfigDto body = r.body();
            if (!r.isSuccessful() || body == null) {
                Log.w(TAG, "config fetch failed: HTTP " + r.code());
                return;
            }
            Snapshot fresh = new Snapshot();
            fresh.flags = body.flags != null ? new HashMap<>(body.flags) : Collections.emptyMap();
            fresh.values = body.values != null ? new HashMap<>(body.values) : Collections.emptyMap();
            fresh.refreshSeconds = body.refreshSeconds > 0 ? body.refreshSeconds : DEFAULT_REFRESH_SECONDS;
            fresh.etag = r.headers().get("ETag");
            fresh.fetchedAtMillis = clock.nowMillis();
            commit(fresh);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "config fetch failed; keeping the last good config", e);
        }
    }

    private void commit(@NonNull Snapshot s) {
        snapshot = s;
        store.save(gson.toJson(s));
    }

    @NonNull
    private Snapshot restore() {
        String json = store.load();
        if (json == null) return new Snapshot();
        try {
            Snapshot s = gson.fromJson(json, Snapshot.class);
            if (s == null) return new Snapshot();
            if (s.flags == null) s.flags = Collections.emptyMap();
            if (s.values == null) s.values = Collections.emptyMap();
            if (s.refreshSeconds <= 0) s.refreshSeconds = DEFAULT_REFRESH_SECONDS;
            return s;
        } catch (JsonParseException e) {
            return new Snapshot(); // a corrupt copy falls back to the defaults
        }
    }

    @NonNull
    private static Snapshot copy(@NonNull Snapshot s) {
        Snapshot c = new Snapshot();
        c.flags = s.flags;
        c.values = s.values;
        c.refreshSeconds = s.refreshSeconds;
        c.etag = s.etag;
        c.fetchedAtMillis = s.fetchedAtMillis;
        return c;
    }
}
