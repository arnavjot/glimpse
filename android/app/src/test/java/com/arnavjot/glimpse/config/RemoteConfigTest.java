package com.arnavjot.glimpse.config;

import static com.google.common.truth.Truth.assertThat;

import com.arnavjot.glimpse.TestSupport;
import com.arnavjot.glimpse.network.ApiInterface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

/** Remote config on the device: defaults, fetching, caching, the kill switch, failures. */
public class RemoteConfigTest {

    private static final String ALL_ON = "{\"flags\":{\"preload\":true,\"share_cards\":true,"
            + "\"push_notifications\":true},\"values\":{\"sponsored_slot\":\"5\"},\"refresh_seconds\":300}";
    private static final String PRELOAD_KILLED = "{\"flags\":{\"preload\":false,\"share_cards\":true,"
            + "\"push_notifications\":true},\"values\":{},\"refresh_seconds\":300}";

    /** In-memory stand-in for SharedPreferences. */
    private static final class MemoryStore implements RemoteConfig.Store {
        String json;

        @Override
        public String load() {
            return json;
        }

        @Override
        public void save(String json) {
            this.json = json;
        }
    }

    private MockWebServer server;
    private ApiInterface api;
    private final MemoryStore store = new MemoryStore();
    private long now = 1_000_000;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        api = TestSupport.api(server);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    private RemoteConfig config() {
        return new RemoteConfig(api, store, () -> now);
    }

    @Test
    public void beforeAnyFetchTheBuiltInDefaultsApply() {
        RemoteConfig c = config();
        for (Flag f : Flag.values()) assertThat(c.isOn(f)).isEqualTo(f.defaultOn);
    }

    @Test
    public void theKillSwitchTurnsAFlagOff() {
        RemoteConfig c = config();
        server.enqueue(new MockResponse().setBody(PRELOAD_KILLED));
        c.refresh();
        assertThat(c.isOn(Flag.PRELOAD)).isFalse();
        assertThat(c.isOn(Flag.SHARE_CARDS)).isTrue();
    }

    @Test
    public void theLastGoodConfigSurvivesARestart() {
        server.enqueue(new MockResponse().setBody(PRELOAD_KILLED));
        config().refresh();
        RemoteConfig afterRestart = config(); // a new instance reads the saved copy, no network
        assertThat(afterRestart.isOn(Flag.PRELOAD)).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    public void aFailedFetchKeepsTheCurrentConfig() {
        RemoteConfig c = config();
        server.enqueue(new MockResponse().setBody(PRELOAD_KILLED));
        c.refresh();
        server.enqueue(new MockResponse().setResponseCode(500));
        c.refresh();
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        c.refresh();
        assertThat(c.isOn(Flag.PRELOAD)).isFalse(); // not reset to the default
    }

    @Test
    public void unchangedConfigIsRevalidatedWithItsEtag() throws Exception {
        RemoteConfig c = config();
        server.enqueue(new MockResponse().setBody(PRELOAD_KILLED).setHeader("ETag", "\"v1\""));
        c.refresh();
        server.enqueue(new MockResponse().setResponseCode(304));
        c.refresh();
        server.takeRequest();
        assertThat(server.takeRequest().getHeader("If-None-Match")).isEqualTo("\"v1\"");
        assertThat(c.isOn(Flag.PRELOAD)).isFalse(); // a 304 keeps the config
    }

    @Test
    public void refreshIfStaleWaitsForTheServersInterval() {
        RemoteConfig c = config();
        server.enqueue(new MockResponse().setBody(ALL_ON));
        c.refreshIfStale(); // never fetched: fetches
        now += 60_000;
        c.refreshIfStale(); // 1 minute later, interval is 5: skipped
        assertThat(server.getRequestCount()).isEqualTo(1);
        now += 300_000;
        server.enqueue(new MockResponse().setBody(PRELOAD_KILLED));
        c.refreshIfStale(); // stale: fetches and picks up the kill switch
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(c.isOn(Flag.PRELOAD)).isFalse();
    }

    @Test
    public void valuesAreRead() {
        RemoteConfig c = config();
        server.enqueue(new MockResponse().setBody(ALL_ON));
        c.refresh();
        assertThat(c.value("sponsored_slot")).isEqualTo("5");
        assertThat(c.value("missing")).isNull();
    }

    @Test
    public void aCorruptSavedCopyFallsBackToDefaults() {
        store.json = "{not json";
        RemoteConfig c = config();
        assertThat(c.isOn(Flag.PRELOAD)).isEqualTo(Flag.PRELOAD.defaultOn);
    }
}
