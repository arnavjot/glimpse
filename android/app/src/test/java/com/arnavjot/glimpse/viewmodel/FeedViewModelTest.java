package com.arnavjot.glimpse.viewmodel;

import static com.google.common.truth.Truth.assertThat;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.core.app.ApplicationProvider;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.analytics.PlaybackAnalytics;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.repository.FeedRepository;
import com.arnavjot.glimpse.repository.LikeSync;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.data.db.AppDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import okhttp3.mockwebserver.MockWebServer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedViewModelTest {

    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private AppDatabase db;
    private MockWebServer server;
    private int refreshes;
    private int loadMores;
    private FeedViewModel vm;

    @Before
    public void setUp() throws Exception {
        db = TestSupport.memoryDb();
        server = new MockWebServer();
        server.start();
        ApiInterface api = TestSupport.api(server);
        LikeSync likeSync = new LikeSync(api, db, new Prefs(ApplicationProvider.getApplicationContext()));
        FeedRepository repo = new FeedRepository(api, db, Runnable::run, likeSync, () -> {}) {
            @Override
            public void refresh() {
                refreshes++;
            }

            @Override
            public void loadMore() {
                loadMores++;
            }
        };
        vm = new FeedViewModel(repo, new PlaybackAnalytics(db, Runnable::run, () -> {}));
    }

    @After
    public void tearDown() throws Exception {
        db.close();
        server.shutdown();
    }

    @Test
    public void theFeedRefreshesWhenOpened() {
        assertThat(refreshes).isEqualTo(1);
    }

    @Test
    public void theNextPageIsFetchedThreeClipsBeforeTheEnd() {
        vm.onPageSelected(5, 10);
        assertThat(loadMores).isEqualTo(0);
        vm.onPageSelected(7, 10);
        assertThat(loadMores).isEqualTo(1);
    }
}
