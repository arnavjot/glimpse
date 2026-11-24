package com.arnavjot.glimpse.repository;

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.NonNull;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.core.app.ApplicationProvider;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.common.Event;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.PendingLike;
import com.arnavjot.glimpse.pojo.VideoEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedRepositoryTest {

    private static final String LIKED_1 = "{\"video_id\":\"a\",\"liked\":true,\"like_count\":1}";
    private static final String UNLIKED_0 = "{\"video_id\":\"a\",\"liked\":false,\"like_count\":0}";

    @Rule public InstantTaskExecutorRule instant = new InstantTaskExecutorRule();

    private MockWebServer server;
    private AppDatabase db;
    private LikeSync likeSync;
    private FeedRepository repo;
    /** True: a like is sent right away (online). False: it only queues, like WorkManager waiting. */
    private boolean syncImmediately = true;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        db = TestSupport.memoryDb();
        ApiInterface api = TestSupport.api(server);
        likeSync = new LikeSync(api, db, new Prefs(ApplicationProvider.getApplicationContext()));
        // Runnable::run: work happens on the test thread, so assertions see it immediately.
        repo = new FeedRepository(api, db, Runnable::run, likeSync, () -> {
            if (syncImmediately) likeSync.syncPending();
        });
    }

    @After
    public void tearDown() throws Exception {
        db.close();
        server.shutdown();
    }

    private List<String> ids() {
        List<String> out = new ArrayList<>();
        for (VideoEntity v : db.videos().feed()) out.add(v.id);
        return out;
    }

    private void loadFeedWithA() {
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "a")));
        repo.refresh();
    }

    // Feed

    @Test
    public void refreshReplacesTheCachedFeedAndRemembersTheCursor() throws Exception {
        server.enqueue(new MockResponse().setBody(TestSupport.page("c1", "a", "b")));
        repo.refresh();
        assertThat(ids()).containsExactly("a", "b").inOrder();
        assertThat(db.videos().cursor().nextCursor).isEqualTo("c1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/feed?limit=10");
    }

    @Test
    public void loadMoreAppendsTheNextPageUsingTheCursor() throws Exception {
        server.enqueue(new MockResponse().setBody(TestSupport.page("c1", "a", "b")));
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "c", "b")));
        repo.refresh();
        repo.loadMore();
        server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertThat(second.getPath()).isEqualTo("/v1/feed?cursor=c1&limit=10");
        assertThat(ids()).containsExactly("a", "b", "c").inOrder(); // "b" not duplicated
        assertThat(repo.state().getValue().endReached).isTrue();
    }

    @Test
    public void aFailedRefreshKeepsTheCachedFeed() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setResponseCode(503));
        repo.refresh();
        assertThat(ids()).containsExactly("a");
        assertThat(repo.state().getValue().error).isEqualTo(FeedRepository.Failure.SERVER);
    }

    @Test
    public void aDroppedConnectionIsANetworkFailure() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        repo.refresh();
        assertThat(repo.state().getValue().error).isEqualTo(FeedRepository.Failure.NETWORK);
    }

    // Likes: online

    @Test
    public void aSuccessfulLikeTakesTheServersCount() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setBody("{\"video_id\":\"a\",\"liked\":true,\"like_count\":42}"));
        repo.setLiked("a", true);
        assertThat(db.videos().get("a").likeCount).isEqualTo(42);
        assertThat(db.pendingLikes().all()).isEmpty();
    }

    @Test
    public void likesSurviveARefresh() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setBody(LIKED_1));
        repo.setLiked("a", true);
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "a")));
        repo.refresh();
        assertThat(db.videos().get("a").liked).isTrue();
    }

    @Test
    public void likingTwiceStaysLikedAndCountsOnce() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setBody(LIKED_1));
        repo.setLiked("a", true);
        repo.setLiked("a", true); // already liked, nothing queued: no second request
        VideoEntity a = db.videos().get("a");
        assertThat(a.liked).isTrue();
        assertThat(a.likeCount).isEqualTo(1);
        assertThat(server.getRequestCount()).isEqualTo(2); // feed + one PUT
    }

    @Test
    public void unlikingForgetsTheLike() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setBody(LIKED_1));
        server.enqueue(new MockResponse().setBody(UNLIKED_0));
        repo.setLiked("a", true);
        repo.setLiked("a", false);
        assertThat(db.videos().likedIds()).isEmpty();
    }

    @Test
    public void aLikeIsRememberedForClipsBeyondTheFirstPage() {
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "z")));
        repo.refresh();
        server.enqueue(new MockResponse().setBody("{\"video_id\":\"z\",\"liked\":true,\"like_count\":1}"));
        repo.setLiked("z", true);
        // A refresh brings a first page without "z"...
        server.enqueue(new MockResponse().setBody(TestSupport.page("c1", "a")));
        repo.refresh();
        assertThat(ids()).containsExactly("a");
        // ...and when "z" comes back on page two, it is still liked.
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "z")));
        repo.loadMore();
        assertThat(db.videos().get("z").liked).isTrue();
    }

    // Likes: offline and failures

    @Test
    public void aLikeMadeOfflineShowsAtOnceAndIsSentLater() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        repo.setLiked("a", true);
        VideoEntity a = db.videos().get("a");
        assertThat(a.liked).isTrue();               // kept on screen, not rolled back
        assertThat(a.likeCount).isEqualTo(1);
        assertThat(db.pendingLikes().get("a")).isNotNull();
        assertThat(repo.likeFailures().getValue()).isNull(); // no error for a retryable failure

        // Back online: WorkManager runs the sync.
        server.enqueue(new MockResponse().setBody("{\"video_id\":\"a\",\"liked\":true,\"like_count\":7}"));
        assertThat(likeSync.syncPending()).isEqualTo(LikeSync.Outcome.DONE);
        assertThat(db.videos().get("a").likeCount).isEqualTo(7);
        assertThat(db.pendingLikes().all()).isEmpty();
    }

    @Test
    public void aServerErrorKeepsTheLikeQueuedForRetry() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setResponseCode(503));
        repo.setLiked("a", true);
        assertThat(db.videos().get("a").liked).isTrue();
        assertThat(db.pendingLikes().get("a")).isNotNull();
        assertThat(repo.likeFailures().getValue()).isNull();
    }

    @Test
    public void aLikeTheServerRejectsForGoodIsUndoneAndReportedOnce() {
        loadFeedWithA();
        server.enqueue(new MockResponse().setResponseCode(404)); // e.g. the clip was deleted
        repo.setLiked("a", true);
        VideoEntity a = db.videos().get("a");
        assertThat(a.liked).isFalse();
        assertThat(a.likeCount).isEqualTo(0);
        assertThat(db.pendingLikes().all()).isEmpty();
        assertThat(db.videos().likedIds()).isEmpty();
        // Reported once, and not as a feed error (the screen must not switch to "offline").
        assertThat(repo.state().getValue().error).isNull();
        Event<FeedRepository.Failure> event = repo.likeFailures().getValue();
        assertThat(event).isNotNull();
        assertThat(event.consume()).isEqualTo(FeedRepository.Failure.SERVER);
        assertThat(event.consume()).isNull();
    }

    @Test
    public void tapsWhileOfflineCollapseIntoOneRequest() throws Exception {
        loadFeedWithA();
        syncImmediately = false;
        repo.setLiked("a", true);
        repo.setLiked("a", false);
        repo.setLiked("a", true);
        PendingLike queued = db.pendingLikes().get("a");
        assertThat(queued.liked).isTrue();
        assertThat(queued.version).isEqualTo(3);

        server.enqueue(new MockResponse().setBody(LIKED_1));
        likeSync.syncPending();
        assertThat(server.getRequestCount()).isEqualTo(2); // feed + one PUT
        server.takeRequest();
        assertThat(server.takeRequest().getMethod()).isEqualTo("PUT");
    }

    @Test
    public void aRefreshDoesNotUndoAQueuedLike() {
        loadFeedWithA();
        syncImmediately = false;
        repo.setLiked("a", true);
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "a"))); // server: 0 likes
        repo.refresh();
        VideoEntity a = db.videos().get("a");
        assertThat(a.liked).isTrue();
        assertThat(a.likeCount).isEqualTo(1);
    }

    @Test
    public void aTapWhileARequestIsInFlightIsNotLost() {
        loadFeedWithA();
        syncImmediately = false;
        repo.setLiked("a", true);
        // While the PUT is on the wire, the user unlikes. The PUT's answer must not win.
        server.setDispatcher(new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                if ("PUT".equals(request.getMethod())) {
                    repo.setLiked("a", false);
                    return new MockResponse().setBody(LIKED_1);
                }
                return new MockResponse().setBody(UNLIKED_0);
            }
        });
        assertThat(likeSync.syncPending()).isEqualTo(LikeSync.Outcome.DONE);
        VideoEntity a = db.videos().get("a");
        assertThat(a.liked).isFalse();
        assertThat(a.likeCount).isEqualTo(0);
        assertThat(db.pendingLikes().all()).isEmpty();
    }

    @Test
    public void onlyNonRetryable4xxArePermanent() {
        assertThat(LikeSync.isPermanent(400)).isTrue();
        assertThat(LikeSync.isPermanent(404)).isTrue();
        assertThat(LikeSync.isPermanent(408)).isFalse();
        assertThat(LikeSync.isPermanent(429)).isFalse();
        assertThat(LikeSync.isPermanent(500)).isFalse();
        assertThat(LikeSync.isPermanent(503)).isFalse();
    }

    // Shared links and refresh

    @Test
    public void aSharedLinkFetchesTheFeedWithThatClipFirst() throws Exception {
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "s", "a")));
        repo.openVideo("s");
        assertThat(server.takeRequest().getPath()).isEqualTo("/v1/feed?limit=10&first=s");
        assertThat(ids()).containsExactly("s", "a").inOrder();
        FeedRepository.Show show = repo.showVideo().getValue().consume();
        assertThat(show.videoId).isEqualTo("s");
        assertThat(show.atTop).isTrue();
    }

    @Test
    public void aSharedLinkOpenedDuringTheLaunchRefreshIsQueuedNotDropped() {
        // While the launch refresh is on the wire, the deep link arrives.
        server.setDispatcher(new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                if (request.getPath().contains("first=s")) {
                    return new MockResponse().setBody(TestSupport.page(null, "s", "a"));
                }
                repo.openVideo("s"); // busy: must be queued
                return new MockResponse().setBody(TestSupport.page(null, "a"));
            }
        });
        repo.refresh();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(ids()).containsExactly("s", "a").inOrder();
    }

    @Test
    public void aSharedClipTheServerDoesNotHaveIsReported() {
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "a"))); // "gone" ignored
        repo.openVideo("gone");
        assertThat(repo.openFailures().getValue().consume()).isEqualTo(FeedRepository.Failure.SERVER);
        assertThat(repo.showVideo().getValue()).isNull();
    }

    @Test
    public void offlineACachedSharedClipIsShownWhereItIs() {
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "a", "b")));
        repo.refresh();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        repo.openVideo("b");
        FeedRepository.Show show = repo.showVideo().getValue().consume();
        assertThat(show.videoId).isEqualTo("b");
        assertThat(show.atTop).isFalse();
    }

    @Test
    public void offlineAnUncachedSharedClipIsReportedAsANetworkFailure() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        repo.openVideo("s");
        assertThat(repo.openFailures().getValue().consume()).isEqualTo(FeedRepository.Failure.NETWORK);
    }

    @Test
    public void pullToRefreshShowsTheNewFirstClipButTheLaunchRefreshDoesNot() {
        loadFeedWithA(); // launch refresh
        assertThat(repo.showVideo().getValue()).isNull();
        server.enqueue(new MockResponse().setBody(TestSupport.page(null, "n", "a")));
        repo.refreshFromUser();
        FeedRepository.Show show = repo.showVideo().getValue().consume();
        assertThat(show.videoId).isEqualTo("n");
        assertThat(show.atTop).isTrue();
    }

    // Sponsored clips

    /** A page with clip "a", then sponsored clip "ad1". */
    private static String pageWithAd() {
        String ad = "{\"id\":\"ad1\",\"title\":\"Ad\",\"creator\":\"Glimpse\",\"duration_ms\":10000,"
                + "\"width\":720,\"height\":1280,\"hls_url\":\"http://media/ad1/index.m3u8\","
                + "\"poster_url\":\"http://media/ad1/poster.jpg\",\"like_count\":0,"
                + "\"sponsored\":true,\"cta_url\":\"https://example.com\"}";
        return "{\"items\":[" + TestSupport.video("a", 0) + "," + ad + "],\"next_cursor\":null}";
    }

    @Test
    public void sponsoredClipsAreKeptWithTheirLink() {
        server.enqueue(new MockResponse().setBody(pageWithAd()));
        repo.refresh();
        VideoEntity ad = db.videos().get("ad1");
        assertThat(ad.sponsored).isTrue();
        assertThat(ad.ctaUrl).isEqualTo("https://example.com");
        assertThat(db.videos().get("a").sponsored).isFalse();
    }

    @Test
    public void goingOfflineRemovesSponsoredClipsButKeepsTheFeed() {
        server.enqueue(new MockResponse().setBody(pageWithAd()));
        repo.refresh();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        repo.refreshFromUser();
        assertThat(ids()).containsExactly("a");
    }

    @Test
    public void sponsoredClipsCannotBeLiked() {
        server.enqueue(new MockResponse().setBody(pageWithAd()));
        repo.refresh();
        repo.setLiked("ad1", true);
        assertThat(db.videos().get("ad1").liked).isFalse();
        assertThat(db.pendingLikes().all()).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(1); // just the feed
    }
}
