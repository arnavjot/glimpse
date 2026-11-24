package com.arnavjot.glimpse.repository;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.arnavjot.glimpse.common.Event;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.data.db.PendingLikeDao;
import com.arnavjot.glimpse.data.db.VideoDao;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.LikeDto;
import com.arnavjot.glimpse.pojo.PendingLike;
import com.arnavjot.glimpse.pojo.VideoEntity;

import java.io.IOException;

import retrofit2.Response;

/**
 * Sends queued likes to the server, oldest first, one at a time. Run by
 * {@link SyncLikesWorker}, which retries with backoff whenever this returns
 * {@link Outcome#RETRY}.
 *
 * <ul>
 *   <li>Success: the row is removed and the server's count applied, unless the user
 *       tapped again meanwhile (then the newer wish is sent next).</li>
 *   <li>No network, 5xx, 408, 429: kept for a later retry. The like stays on screen.</li>
 *   <li>Any other 4xx (e.g. the clip was deleted): it can never succeed, so it is
 *       dropped, the like is rolled back and {@link #failures()} reports it once.</li>
 * </ul>
 */
public class LikeSync {

    public enum Outcome { DONE, RETRY }

    private final ApiInterface api;
    private final AppDatabase db;
    private final PendingLikeDao queue;
    private final VideoDao videos;
    private final Prefs prefs;
    private final MutableLiveData<Event<FeedRepository.Failure>> failures = new MutableLiveData<>();

    public LikeSync(@NonNull ApiInterface api, @NonNull AppDatabase db, @NonNull Prefs prefs) {
        this.api = api;
        this.db = db;
        this.queue = db.pendingLikes();
        this.videos = db.videos();
        this.prefs = prefs;
    }

    /** A like the server rejected for good (already rolled back). Show it once. */
    public LiveData<Event<FeedRepository.Failure>> failures() {
        return failures;
    }

    /** Sends everything queued. Blocking: call it off the main thread. */
    @NonNull
    public Outcome syncPending() {
        PendingLike next;
        while ((next = queue.oldest()) != null) {
            final PendingLike sent = next;
            Response<LikeDto> r;
            try {
                r = sent.liked
                        ? api.like(sent.videoId, new LikeDto.Body(prefs.deviceId())).execute()
                        : api.unlike(sent.videoId, prefs.deviceId()).execute();
            } catch (IOException e) {
                return Outcome.RETRY;
            }
            LikeDto body = r.body();
            if (r.isSuccessful() && body != null) {
                db.runInTransaction(() -> {
                    // Applied only if no newer tap replaced the row while we waited.
                    if (queue.deleteIfUnchanged(sent.videoId, sent.version) == 1) {
                        videos.setLike(sent.videoId, body.liked, body.likeCount);
                    }
                });
            } else if (isPermanent(r.code())) {
                boolean dropped = db.runInTransaction(() -> {
                    if (queue.deleteIfUnchanged(sent.videoId, sent.version) == 0) return false;
                    rollBack(sent);
                    return true;
                });
                if (dropped) failures.postValue(new Event<>(FeedRepository.Failure.SERVER));
            } else {
                return Outcome.RETRY;
            }
        }
        return Outcome.DONE;
    }

    /** Undo the optimistic write for a wish the server will never accept. */
    private void rollBack(@NonNull PendingLike sent) {
        VideoEntity v = videos.get(sent.videoId);
        if (v == null) {
            videos.setLike(sent.videoId, !sent.liked, 0); // not cached: fix the liked set only
        } else if (v.liked == sent.liked) {
            videos.setLike(sent.videoId, !sent.liked, Math.max(0, v.likeCount + (sent.liked ? -1 : 1)));
        }
    }

    /** 4xx other than timeout (408) and rate limit (429): retrying won't help. */
    static boolean isPermanent(int code) {
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }
}
