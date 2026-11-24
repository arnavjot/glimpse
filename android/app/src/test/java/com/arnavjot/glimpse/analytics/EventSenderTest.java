package com.arnavjot.glimpse.analytics;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.pojo.PendingEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EventSenderTest {

    private MockWebServer server;
    private AppDatabase db;
    private EventSender sender;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        db = TestSupport.memoryDb();
        sender = new EventSender(TestSupport.api(server), db,
                new Prefs(ApplicationProvider.getApplicationContext()));
    }

    @After
    public void tearDown() throws Exception {
        db.close();
        server.shutdown();
    }

    private void queue(int n) {
        for (int i = 0; i < n; i++) {
            PendingEvent e = new PendingEvent();
            e.eventId = "event-" + i;
            e.videoId = "v001";
            e.type = PlaybackAnalytics.FIRST_FRAME;
            e.valueMs = 50L;
            e.preload = true;
            e.sessionId = "s";
            e.occurredAt = 1_700_000_000_000L + i;
            db.events().insert(e);
        }
    }

    private static MockResponse ok(int accepted) {
        return new MockResponse().setBody("{\"accepted\":" + accepted + ",\"duplicates\":0}");
    }

    @Test
    public void drainsTheQueueInBatchesOfAHundred() throws Exception {
        queue(250);
        server.enqueue(ok(100));
        server.enqueue(ok(100));
        server.enqueue(ok(50));
        assertThat(sender.sendPending()).isEqualTo(EventSender.Outcome.DONE);
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(db.events().count()).isEqualTo(0);
        assertThat(sender.lastAccepted).isEqualTo(250);
        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"event_id\":\"event-0\"");
        assertThat(body).contains("\"occurred_at\":\"2023-11-14T22:13:20.000Z\"");
    }

    @Test
    public void aServerErrorKeepsTheEventsForARetry() {
        queue(5);
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThat(sender.sendPending()).isEqualTo(EventSender.Outcome.RETRY);
        assertThat(db.events().count()).isEqualTo(5);
    }

    @Test
    public void aLostResponseIsRetriedNotDropped() {
        queue(5);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        assertThat(sender.sendPending()).isEqualTo(EventSender.Outcome.RETRY);
        assertThat(db.events().count()).isEqualTo(5);
    }

    @Test
    public void aRejectedBatchIsDroppedSoItCantBlockTheQueue() {
        queue(5);
        server.enqueue(new MockResponse().setResponseCode(422));
        assertThat(sender.sendPending()).isEqualTo(EventSender.Outcome.DONE);
        assertThat(sender.lastDropped).isEqualTo(5);
        assertThat(db.events().count()).isEqualTo(0);
    }

    @Test
    public void rateLimitedIsRetriedLater() {
        queue(1);
        server.enqueue(new MockResponse().setResponseCode(429));
        assertThat(sender.sendPending()).isEqualTo(EventSender.Outcome.RETRY);
        assertThat(db.events().count()).isEqualTo(1);
    }
}
