package com.arnavjot.glimpse.player;

import androidx.annotation.NonNull;

/**
 * How much of each feed item to load ahead of time, by its distance from the
 * clip on screen. Spending bandwidth on the next clip is what makes swiping
 * instant; spending it on clips five swipes away mostly wastes data and battery.
 *
 * <pre>
 *  distance  +1  load the first {@link #NEXT_CLIP_MS} ms of media
 *  distance  +2  select tracks (manifest parsed, ready to load)
 *  distance  -1, +3  prepare the source only (cheap: playlist fetched)
 *  further       nothing; release what was preloaded
 * </pre>
 */
public final class PreloadPolicy {

    /** Two 2-second HLS segments: enough to start instantly and absorb a slow start. */
    public static final long NEXT_CLIP_MS = 3000;

    public enum Stage { LOAD_FOR_DURATION, TRACKS_SELECTED, SOURCE_PREPARED, NONE }

    public static final class Target {
        @NonNull public final Stage stage;
        public final long durationMs;

        Target(@NonNull Stage stage, long durationMs) {
            this.stage = stage;
            this.durationMs = durationMs;
        }
    }

    private PreloadPolicy() {}

    @NonNull
    public static Target forDistance(int distance) {
        if (distance == 1) return new Target(Stage.LOAD_FOR_DURATION, NEXT_CLIP_MS);
        if (distance == 2) return new Target(Stage.TRACKS_SELECTED, 0);
        if (distance == -1 || distance == 3) return new Target(Stage.SOURCE_PREPARED, 0);
        return new Target(Stage.NONE, 0);
    }
}
