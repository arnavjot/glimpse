package com.arnavjot.glimpse.di;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.room.Room;

import com.arnavjot.glimpse.analytics.EventSender;
import com.arnavjot.glimpse.config.RemoteConfig;
import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.analytics.UploadEventsWorker;
import com.arnavjot.glimpse.repository.FeedRepository;
import com.arnavjot.glimpse.repository.LikeSync;
import com.arnavjot.glimpse.repository.SyncLikesWorker;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.player.MediaCacheStats;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module(includes = NetworkModule.class)
@OptIn(markerClass = UnstableApi.class)
public class AppModule {

    /** Disk budget for video segments. LRU eviction keeps it bounded. */
    static final long MEDIA_CACHE_BYTES = 200L * 1024 * 1024;

    private final Context app;

    public AppModule(Context app) {
        this.app = app.getApplicationContext();
    }

    @Provides
    @Singleton
    Context context() {
        return app;
    }

    @Provides
    @Singleton
    Prefs prefs() {
        return new Prefs(app);
    }

    @Provides
    @Singleton
    @Named("io")
    ExecutorService io() {
        return Executors.newFixedThreadPool(4);
    }

    @Provides
    @Singleton
    AppDatabase database() {
        return Room.databaseBuilder(app, AppDatabase.class, "glimpse.db")
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .build();
    }

    /** Feature flags; the last good config is kept in its own SharedPreferences file. */
    @Provides
    @Singleton
    RemoteConfig remoteConfig(ApiInterface api) {
        SharedPreferences file = app.getSharedPreferences("remote_config", Context.MODE_PRIVATE);
        RemoteConfig.Store store = new RemoteConfig.Store() {
            @Override
            public String load() {
                return file.getString("snapshot", null);
            }

            @Override
            public void save(@NonNull String json) {
                file.edit().putString("snapshot", json).apply();
            }
        };
        return new RemoteConfig(api, store, System::currentTimeMillis);
    }

    @Provides
    @Singleton
    LikeSync likeSync(ApiInterface api, AppDatabase db, Prefs prefs) {
        return new LikeSync(api, db, prefs);
    }

    @Provides
    @Singleton
    FeedRepository feedRepository(ApiInterface api, AppDatabase db, @Named("io") ExecutorService io,
                                  LikeSync likeSync) {
        return new FeedRepository(api, db, io, likeSync, () -> SyncLikesWorker.enqueue(app));
    }

    @Provides
    @Singleton
    PlaybackAnalytics analytics(AppDatabase db, @Named("io") ExecutorService io) {
        return new PlaybackAnalytics(db, io, () -> UploadEventsWorker.enqueue(app));
    }

    @Provides
    @Singleton
    EventSender eventSender(ApiInterface api, AppDatabase db, Prefs prefs) {
        return new EventSender(api, db, prefs);
    }

    @Provides
    @Singleton
    MediaCacheStats cacheStats() {
        return new MediaCacheStats();
    }

    @Provides
    @Singleton
    SimpleCache mediaCache() {
        return new SimpleCache(
                new File(app.getCacheDir(), "media"),
                new LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_BYTES),
                new StandaloneDatabaseProvider(app));
    }

    /** Every media read goes through the disk cache; misses go to the network. */
    @Provides
    @Singleton
    DataSource.Factory cachedDataSource(SimpleCache cache, MediaCacheStats stats) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(10000)
                .setTransferListener(stats);
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(app, http))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(stats);
    }
}
