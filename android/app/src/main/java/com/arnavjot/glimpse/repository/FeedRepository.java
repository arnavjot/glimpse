package com.arnavjot.glimpse.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.arnavjot.glimpse.common.Event;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.data.db.PendingLikeDao;
import com.arnavjot.glimpse.data.db.VideoDao;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.FeedCursor;
import com.arnavjot.glimpse.pojo.FeedPageDto;
import com.arnavjot.glimpse.pojo.PendingLike;
import com.arnavjot.glimpse.pojo.VideoDto;
import com.arnavjot.glimpse.pojo.VideoEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

/**
 * Offline-first feed. The UI only ever reads Room; the network refreshes Room.
 * So the last feed shows instantly on launch, survives no signal, and a failed
 * refresh never blanks the screen.
 *
 * <p>Likes are offline-first too: a tap is written to Room and to a queue in one
 * transaction, and {@link LikeSync} sends the queue when there is a network.
 */
public class FeedRepository {

    public static final int PAGE_SIZE = 10;

    /** Why a request failed. The UI turns this into words; the repository never does. */
    public enum Failure {
        /** No connection, timeout, or the server address is wrong. */
        NETWORK,
        /** The server answered with an error. */
        SERVER
    }

    /** What the screen shows besides the list itself. */
    public static final class State {
        public final boolean loading;
        public final boolean endReached;
        @Nullable public final Failure error;

        public State(boolean loading, boolean endReached, @Nullable Failure error) {
            this.loading = loading;
            this.endReached = endReached;
            this.error = error;
        }
    }

    /** Where the screen should go after a refresh: to {@code videoId}, at the top if {@code atTop}. */
    public static final class Show {
        @NonNull public final String videoId;
        public final boolean atTop;

        public Show(@NonNull String videoId, boolean atTop) {
            this.videoId = videoId;
            this.atTop = atTop;
        }
    }

    /** Schedules a like sync; abstracted so tests can run it inline instead of via WorkManager. */
    public interface SyncTrigger {
        void requestSync();
    }

    private final ApiInterface api;
    private final AppDatabase db;
    private final VideoDao videos;
    private final PendingLikeDao pendingLikes;
    private final Executor io;
    private final LikeSync likeSync;
    private final SyncTrigger syncTrigger;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, false, null));
    private final MutableLiveData<Event<Failure>> openFailures = new MutableLiveData<>();
    private final MutableLiveData<Event<Show>> showVideo = new MutableLiveData<>();

    // A refresh asked for while another refresh or a page load is running is queued and
    // runs when that one ends, so a shared link opened during the launch refresh is never
    // dropped. Guarded by refreshLock.
    private final Object refreshLock = new Object();
    private boolean refreshQueued;
    /** Last session's sponsored clips are dropped once, before this process's first refresh. */
    private final AtomicBoolean droppedOldSponsored = new AtomicBoolean(false);
    @Nullable private String queuedFirst;
    private boolean queuedJump;

    public FeedRepository(ApiInterface api, AppDatabase db, Executor io,
                          LikeSync likeSync, SyncTrigger syncTrigger) {
        this.api = api;
        this.db = db;
        this.videos = db.videos();
        this.pendingLikes = db.pendingLikes();
        this.io = io;
        this.likeSync = likeSync;
        this.syncTrigger = syncTrigger;
    }

    public LiveData<List<VideoEntity>> feed() {
        return videos.observeFeed();
    }

    public LiveData<State> state() {
        return state;
    }

    /** A like the server rejected for good (already rolled back). Show it once. */
    public LiveData<Event<Failure>> likeFailures() {
        return likeSync.failures();
    }

    /** A shared clip that couldn't be opened (SERVER: it doesn't exist). Show it once. */
    public LiveData<Event<Failure>> openFailures() {
        return openFailures;
    }

    /** One-shot: scroll to this clip (a shared link was opened, or the user refreshed). */
    public LiveData<Event<Show>> showVideo() {
        return showVideo;
    }

    /** Refresh at launch: the viewer stays where they are. */
    public void refresh() {
        requestRefresh(null, false);
    }

    /** Pull to refresh: the new first clip is shown. */
    public void refreshFromUser() {
        requestRefresh(null, true);
    }

    /**
     * A shared link: the first page is fetched with this clip first (GET /v1/feed?first=),
     * so it plays at the top whether or not it was cached. Offline, a cached clip is still
     * shown where it is; anything else is reported through {@link #openFailures()}.
     */
    public void openVideo(@NonNull String videoId) {
        requestRefresh(videoId, true);
    }

    private void requestRefresh(@Nullable String first, boolean jump) {
        synchronized (refreshLock) {
            refreshQueued = true;
            if (first != null) queuedFirst = first;
            queuedJump |= jump;
        }
        runQueuedRefresh();
    }

    /** Starts the queued refresh, unless a refresh or page load is running (it calls this when done). */
    private void runQueuedRefresh() {
        final String first;
        final boolean jump;
        synchronized (refreshLock) {
            if (!refreshQueued || !busy.compareAndSet(false, true)) return;
            refreshQueued = false;
            first = queuedFirst;
            jump = queuedJump;
            queuedFirst = null;
            queuedJump = false;
        }
        state.postValue(new State(true, false, null));
        io.execute(() -> {
            if (droppedOldSponsored.compareAndSet(false, true)) videos.deleteSponsored();
            try {
                FeedPageDto page = fetch(null, first);
                // One transaction: a like written between reading the liked set and
                // replacing the feed can't be overwritten by the server's copy.
                List<VideoEntity> entities = db.runInTransaction(() -> {
                    List<VideoEntity> fresh = toEntities(page.items, 0, new HashSet<>(videos.likedIds()), null);
                    keepQueuedLikes(fresh);
                    videos.replaceFeed(fresh, page.nextCursor);
                    return fresh;
                });
                state.postValue(new State(false, page.nextCursor == null, null));
                if (first != null) {
                    // The server ignores an unknown id, so "not first" means "no such clip".
                    if (!entities.isEmpty() && entities.get(0).id.equals(first)) {
                        showVideo.postValue(new Event<>(new Show(first, true)));
                    } else {
                        openFailures.postValue(new Event<>(Failure.SERVER));
                    }
                } else if (jump && !entities.isEmpty()) {
                    showVideo.postValue(new Event<>(new Show(entities.get(0).id, true)));
                }
            } catch (IOException e) {
                videos.deleteSponsored(); // offline (or failing): ads only when online
                state.postValue(new State(false, false, failureOf(e)));
                if (first != null) {
                    if (videos.get(first) != null) {
                        showVideo.postValue(new Event<>(new Show(first, false)));
                    } else {
                        openFailures.postValue(new Event<>(failureOf(e)));
                    }
                }
            } finally {
                busy.set(false);
                runQueuedRefresh();
            }
        });
    }

    /** Append the next page, if there is one. Called as the user nears the end. */
    public void loadMore() {
        if (!busy.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                FeedCursor cursor = videos.cursor();
                if (cursor == null || cursor.nextCursor == null) {
                    state.postValue(new State(false, cursor != null, null));
                    return;
                }
                state.postValue(new State(true, false, null));
                FeedPageDto page = fetch(cursor.nextCursor, null);
                db.runInTransaction(() -> {
                    Set<String> present = new HashSet<>();
                    for (VideoEntity v : videos.feed()) present.add(v.id);
                    List<VideoEntity> entities = toEntities(page.items, videos.maxPosition() + 1,
                            new HashSet<>(videos.likedIds()), present);
                    keepQueuedLikes(entities);
                    videos.appendPage(entities, page.nextCursor);
                });
                state.postValue(new State(false, page.nextCursor == null, null));
            } catch (IOException e) {
                videos.deleteSponsored(); // offline (or failing): ads only when online
                state.postValue(new State(false, false, failureOf(e)));
            } finally {
                busy.set(false);
                runQueuedRefresh();
            }
        });
    }

    /**
     * Sets the like to the state the user asked for (not a toggle, so a double tap or a
     * refresh in between can't flip it the wrong way). Works offline: the change shows
     * at once and is sent when there is a network.
     */
    public void setLiked(@NonNull String videoId, boolean liked) {
        io.execute(() -> {
            boolean queued = db.runInTransaction(() -> {
                VideoEntity v = videos.get(videoId);
                if (v == null || v.sponsored) return false; // sponsored clips can't be liked
                PendingLike previous = pendingLikes.get(videoId);
                // Already in that state and nothing in flight: the server agrees already.
                if (previous == null && v.liked == liked) return false;
                if (v.liked != liked) {
                    videos.setLike(videoId, liked, Math.max(0, v.likeCount + (liked ? 1 : -1)));
                }
                long version = previous == null ? 1 : previous.version + 1;
                pendingLikes.upsert(new PendingLike(videoId, liked, version, System.currentTimeMillis()));
                return true;
            });
            if (queued) syncTrigger.requestSync();
        });
    }

    /** Clips with a queued like keep their local (optimistic) count over the server's. */
    private void keepQueuedLikes(@NonNull List<VideoEntity> entities) {
        List<PendingLike> queued = pendingLikes.all();
        if (queued.isEmpty()) return;
        Set<String> ids = new HashSet<>();
        for (PendingLike p : queued) ids.add(p.videoId);
        for (VideoEntity e : entities) {
            if (!ids.contains(e.id)) continue;
            VideoEntity local = videos.get(e.id);
            if (local != null) e.likeCount = local.likeCount;
        }
    }

    @NonNull
    private FeedPageDto fetch(@Nullable String cursor, @Nullable String first) throws IOException {
        Response<FeedPageDto> r = api.feed(cursor, PAGE_SIZE, first).execute();
        FeedPageDto body = r.body();
        if (!r.isSuccessful() || body == null || body.items == null) throw new ServerError(r.code());
        return body;
    }

    static List<VideoEntity> toEntities(
            List<VideoDto> items, int firstPosition, Set<String> liked, @Nullable Set<String> skip) {
        List<VideoEntity> out = new ArrayList<>(items.size());
        int position = firstPosition;
        for (VideoDto d : items) {
            if (skip != null && skip.contains(d.id)) continue;
            VideoEntity e = new VideoEntity();
            e.id = d.id;
            e.position = position++;
            e.title = d.title;
            e.creator = d.creator;
            e.durationMs = d.durationMs;
            e.hlsUrl = d.hlsUrl;
            e.posterUrl = d.posterUrl;
            e.likeCount = d.likeCount;
            e.liked = liked.contains(d.id);
            e.sponsored = d.sponsored;
            e.ctaUrl = d.sponsored ? d.ctaUrl : null;
            out.add(e);
        }
        return out;
    }

    @NonNull
    static Failure failureOf(@NonNull IOException e) {
        return e instanceof ServerError ? Failure.SERVER : Failure.NETWORK;
    }

    /** The server answered, but not with what we asked for. */
    static final class ServerError extends IOException {
        final int code;

        ServerError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }
}
