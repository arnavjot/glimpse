package com.arnavjot.glimpse.viewmodel;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.analytics.Percentiles;
import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.pojo.LocalSample;
import com.arnavjot.glimpse.player.MediaCacheStats;

import java.util.List;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

/** Summarises this device's playback measurements: the numbers for the README. */
public class PerformanceViewModel extends ViewModel {

    public static final class Report {
        public int preloadedCount;
        @Nullable public Long preloadedP50;
        @Nullable public Long preloadedP95;
        public int coldCount;
        @Nullable public Long coldP50;
        @Nullable public Long coldP95;
        public int plays;
        public int rebuffers;
        public double byteHitRatio;
        public long cacheBytes;
        public long networkBytes;
    }

    private final AppDatabase db;
    private final ExecutorService io;
    private final MediaCacheStats cacheStats;
    private final PlaybackAnalytics analytics;
    private final MutableLiveData<Report> report = new MutableLiveData<>();

    @Inject
    public PerformanceViewModel(AppDatabase db, @Named("io") ExecutorService io,
                                MediaCacheStats cacheStats, PlaybackAnalytics analytics) {
        this.db = db;
        this.io = io;
        this.cacheStats = cacheStats;
        this.analytics = analytics;
        reload();
    }

    public LiveData<Report> report() {
        return report;
    }

    public LiveData<Integer> pendingUploads() {
        return db.events().observeCount();
    }

    public void reload() {
        io.execute(() -> report.postValue(build()));
    }

    public void uploadNow() {
        analytics.flush();
    }

    public void reset() {
        io.execute(() -> {
            db.samples().clear();
            cacheStats.reset();
            report.postValue(build());
        });
    }

    private Report build() {
        analytics.pruneSamples(); // the screen shows the last 7 days only
        Report r = new Report();
        List<Long> warm = db.samples().values(LocalSample.FIRST_FRAME, true);
        List<Long> cold = db.samples().values(LocalSample.FIRST_FRAME, false);
        r.preloadedCount = warm.size();
        r.preloadedP50 = Percentiles.of(warm, 50);
        r.preloadedP95 = Percentiles.of(warm, 95);
        r.coldCount = cold.size();
        r.coldP50 = Percentiles.of(cold, 50);
        r.coldP95 = Percentiles.of(cold, 95);
        r.plays = db.samples().count(LocalSample.PLAY_START);
        r.rebuffers = db.samples().count(LocalSample.REBUFFER);
        r.byteHitRatio = cacheStats.byteHitRatio();
        r.cacheBytes = cacheStats.cacheBytes();
        r.networkBytes = cacheStats.networkBytes();
        return r;
    }
}
