package com.arnavjot.glimpse.analytics;

import static com.google.common.truth.Truth.assertThat;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.pojo.LocalSample;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaybackAnalyticsTest {

    private AppDatabase db;
    private int uploads;
    private PlaybackAnalytics analytics;

    @Before
    public void setUp() {
        db = TestSupport.memoryDb();
        analytics = new PlaybackAnalytics(db, Runnable::run, () -> uploads++);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void eachEventIsQueuedWithItsOwnId() {
        analytics.record(PlaybackAnalytics.FIRST_FRAME, "v1", 42L, true);
        analytics.record(PlaybackAnalytics.FIRST_FRAME, "v1", 42L, true);
        Set<String> ids = new HashSet<>();
        db.events().oldest(10).forEach(e -> ids.add(e.eventId));
        assertThat(ids).hasSize(2);
    }

    @Test
    public void firstFrameTimesAreKeptLocallySplitByPreload() {
        analytics.record(PlaybackAnalytics.FIRST_FRAME, "v1", 40L, true);
        analytics.record(PlaybackAnalytics.FIRST_FRAME, "v2", 400L, false);
        analytics.record(PlaybackAnalytics.WATCH, "v1", 9000L, true); // not a local sample
        List<Long> warm = db.samples().values(LocalSample.FIRST_FRAME, true);
        List<Long> cold = db.samples().values(LocalSample.FIRST_FRAME, false);
        assertThat(warm).containsExactly(40L);
        assertThat(cold).containsExactly(400L);
    }

    @Test
    public void uploadsStartOnceEnoughEventsAreWaiting() {
        for (int i = 0; i < PlaybackAnalytics.UPLOAD_THRESHOLD - 1; i++) {
            analytics.record(PlaybackAnalytics.PLAY_START, "v1", null, true);
        }
        assertThat(uploads).isEqualTo(0);
        analytics.record(PlaybackAnalytics.PLAY_START, "v1", null, true);
        assertThat(uploads).isEqualTo(1);
    }

    @Test
    public void theQueueIsCappedWhenOfflineForLong() {
        for (int i = 0; i < PlaybackAnalytics.MAX_QUEUED + 20; i++) {
            analytics.record(PlaybackAnalytics.PLAY_START, "v1", null, true);
        }
        assertThat(db.events().count()).isAtMost(PlaybackAnalytics.MAX_QUEUED);
    }

    @Test
    public void timingSamplesOlderThanTheWindowArePruned() {
        long[] now = {0};
        PlaybackAnalytics timed = new PlaybackAnalytics(db, Runnable::run, () -> {}, () -> now[0]);
        now[0] = TimeUnit.DAYS.toMillis(1);
        timed.record(PlaybackAnalytics.FIRST_FRAME, "old", 500L, false);
        now[0] = TimeUnit.DAYS.toMillis(10); // nine days later
        timed.record(PlaybackAnalytics.FIRST_FRAME, "new", 50L, false);
        timed.pruneSamples();
        assertThat(db.samples().values(LocalSample.FIRST_FRAME, false)).containsExactly(50L);
    }

    @Test
    public void timingSamplesAreCappedKeepingTheNewest() {
        long[] now = {TimeUnit.DAYS.toMillis(100)};
        PlaybackAnalytics timed = new PlaybackAnalytics(db, Runnable::run, () -> {}, () -> now[0]++);
        for (int i = 0; i < 5; i++) timed.record(PlaybackAnalytics.FIRST_FRAME, "v" + i, (long) i, false);
        db.samples().prune(0, 3);
        assertThat(db.samples().values(LocalSample.FIRST_FRAME, false)).containsExactly(2L, 3L, 4L);
    }
}
