package com.arnavjot.glimpse.player;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl;
import androidx.media3.ui.PlayerView;

import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.pojo.VideoEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One ExoPlayer shared by the whole feed, plus a preload manager that loads the
 * clips around the current one in the background.
 *
 * <p>One player instead of one per page: decoders are expensive and limited, so
 * the player moves between pages' PlayerViews with {@link PlayerView#switchTargetView}
 * and swaps in an already-preloaded MediaSource for the new clip. Both come
 * from the same builder so they share the same load control, bandwidth meter
 * and track selector.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FeedPlayer {

    /** Tells the UI (debug overlay) how fast the clip started. */
    public interface FirstFrameListener {
        void onFirstFrame(@NonNull String videoId, long millis, boolean preloaded);
    }

    private final ExoPlayer player;
    private final DefaultPreloadManager preloadManager;
    private final MediaSource.Factory directFactory;
    private final PlaybackAnalytics analytics;
    private final boolean preloadEnabled;
    private final DistanceControl control = new DistanceControl();
    private final Map<String, MediaItem> items = new HashMap<>();
    private final Map<String, Integer> indexOf = new HashMap<>();

    @Nullable private PlayerView attachedView;
    @Nullable private String currentId;
    @Nullable private FirstFrameListener firstFrameListener;
    private long selectedAtMs;
    /** The viewer paused this clip by tapping; cleared when another clip starts. */
    private boolean userPaused;
    private boolean firstFrameSeen;
    private long playingSinceMs = -1;
    private long watchedMs;

    public FeedPlayer(Context context, DataSource.Factory cachedDataSource,
                      PlaybackAnalytics analytics, boolean preloadEnabled) {
        this.analytics = analytics;
        this.preloadEnabled = preloadEnabled;
        this.directFactory = new DefaultMediaSourceFactory(cachedDataSource);
        DefaultPreloadManager.Builder builder = new DefaultPreloadManager.Builder(context, control)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cachedDataSource));
        this.player = builder.buildExoPlayer();
        this.preloadManager = builder.build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Listener());
    }

    public void setFirstFrameListener(@Nullable FirstFrameListener listener) {
        this.firstFrameListener = listener;
    }

    public boolean preloadEnabled() {
        return preloadEnabled;
    }

    /** Keep the preload manager's set of items in step with the feed. */
    public void setFeed(@NonNull List<VideoEntity> feed) {
        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < feed.size(); i++) {
            VideoEntity v = feed.get(i);
            wanted.add(v.id);
            MediaItem item = items.get(v.id);
            Integer was = indexOf.get(v.id);
            if (item == null) {
                item = new MediaItem.Builder().setMediaId(v.id).setUri(v.hlsUrl).build();
                items.put(v.id, item);
            } else if (was != null && was != i && preloadEnabled) {
                // A refresh moved it: re-register so its preload rank follows its new position.
                preloadManager.remove(item);
                was = null;
            }
            if (was == null || was != i) {
                indexOf.put(v.id, i);
                if (preloadEnabled) preloadManager.add(item, i);
            }
        }
        for (String id : new HashSet<>(items.keySet())) {
            if (!wanted.contains(id)) {
                MediaItem gone = items.remove(id);
                indexOf.remove(id);
                if (gone != null && preloadEnabled) preloadManager.remove(gone);
            }
        }
        if (preloadEnabled) preloadManager.invalidate();
    }

    /** Play the clip at {@code index}, moving the shared player onto {@code view}. */
    public void play(int index, @NonNull String videoId, @NonNull PlayerView view) {
        if (videoId.equals(currentId)) {
            // Same clip. If its page view was rebound, move the surface over and keep
            // playing from where it is; never restart, and never resume a paused player.
            if (view != attachedView) {
                PlayerView.switchTargetView(player, attachedView, view);
                attachedView = view;
            }
            return;
        }
        leaveCurrent();
        MediaItem item = items.get(videoId);
        if (item == null) return;

        PlayerView.switchTargetView(player, attachedView, view);
        attachedView = view;
        currentId = videoId;
        userPaused = false;
        selectedAtMs = SystemClock.elapsedRealtime();
        firstFrameSeen = false;
        watchedMs = 0;

        MediaSource source = preloadEnabled ? preloadManager.getMediaSource(item) : null;
        if (source == null) source = directFactory.createMediaSource(item);
        player.setMediaSource(source);
        player.prepare();
        player.play();
        analytics.record(PlaybackAnalytics.PLAY_START, videoId, null, preloadEnabled);

        control.currentIndex = index;
        if (preloadEnabled) {
            preloadManager.setCurrentPlayingIndex(index);
            preloadManager.invalidate();
        }
    }

    public void pause() {
        player.pause();
    }

    /** Back in the foreground: continue, unless the viewer had paused this clip. */
    public void resume() {
        if (currentId != null && !userPaused) player.play();
    }

    /** Tap on the video: pause or continue. @return true if now paused by the viewer. */
    public boolean togglePause() {
        if (currentId == null) return false;
        userPaused = !userPaused;
        if (userPaused) {
            player.pause();
        } else {
            player.play();
        }
        return userPaused;
    }

    public boolean isUserPaused() {
        return userPaused;
    }

    public void setMuted(boolean muted) {
        player.setVolume(muted ? 0f : 1f);
    }

    /** How far into the current clip, 0..1 (0 before its duration is known). */
    public float progress() {
        long duration = player.getDuration();
        if (currentId == null || duration == C.TIME_UNSET || duration <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (float) player.getCurrentPosition() / duration));
    }

    /** Release everything. Call from onDestroy. */
    public void release() {
        leaveCurrent();
        preloadManager.release();
        player.release();
        attachedView = null;
    }

    private void leaveCurrent() {
        if (currentId == null) return;
        if (playingSinceMs >= 0) {
            watchedMs += SystemClock.elapsedRealtime() - playingSinceMs;
            playingSinceMs = -1;
        }
        if (watchedMs > 0) analytics.record(PlaybackAnalytics.WATCH, currentId, watchedMs, preloadEnabled);
        player.stop();
        currentId = null;
    }

    private final class Listener implements Player.Listener {
        @Override
        public void onRenderedFirstFrame() {
            if (firstFrameSeen || currentId == null) return;
            firstFrameSeen = true;
            long ms = SystemClock.elapsedRealtime() - selectedAtMs;
            analytics.record(PlaybackAnalytics.FIRST_FRAME, currentId, ms, preloadEnabled);
            if (firstFrameListener != null) firstFrameListener.onFirstFrame(currentId, ms, preloadEnabled);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            // Buffering after the clip has started is a stall the viewer sees.
            if (state == Player.STATE_BUFFERING && firstFrameSeen && currentId != null) {
                analytics.record(PlaybackAnalytics.REBUFFER, currentId, null, preloadEnabled);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            long now = SystemClock.elapsedRealtime();
            if (isPlaying) {
                playingSinceMs = now;
            } else if (playingSinceMs >= 0) {
                watchedMs += now - playingSinceMs;
                playingSinceMs = -1;
            }
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition, int reason) {
            // With REPEAT_MODE_ONE, looping back to the start means the clip was watched to the end.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && currentId != null) {
                analytics.record(PlaybackAnalytics.COMPLETE, currentId, null, preloadEnabled);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            if (currentId != null) {
                analytics.record(PlaybackAnalytics.ERROR, currentId, (long) error.errorCode, preloadEnabled);
            }
        }
    }

    /** Maps each item's distance from the current one to a preload target. */
    private static final class DistanceControl implements TargetPreloadStatusControl<Integer> {
        volatile int currentIndex;

        @Nullable
        @Override
        public DefaultPreloadManager.Status getTargetPreloadStatus(Integer index) {
            PreloadPolicy.Target t = PreloadPolicy.forDistance(index - currentIndex);
            switch (t.stage) {
                case LOAD_FOR_DURATION:
                    return new DefaultPreloadManager.Status(
                            DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS, t.durationMs);
                case TRACKS_SELECTED:
                    return new DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_TRACKS_SELECTED);
                case SOURCE_PREPARED:
                    return new DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_SOURCE_PREPARED);
                default:
                    return null;
            }
        }
    }
}
