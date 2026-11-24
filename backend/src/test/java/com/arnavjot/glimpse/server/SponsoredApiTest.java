package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/** Sponsored clips in the feed: placement, per-device cap, rotation. Slot 5, cap 6 (application.yml). */
class SponsoredApiTest extends IntegrationTest {

    private static final String DEVICE = "device-0001";

    private void sponsor(String id) {
        jdbc.update("INSERT INTO sponsored_clips (id, title, advertiser, duration_ms, width, height, hls_path, "
                + "poster_path, cta_url) VALUES (?, 'Ad', 'Glimpse', 10000, 720, 1280, ?, ?, 'https://example.com')",
                id, id + "/index.m3u8", id + "/poster.jpg");
    }

    private void impressions(String adId, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO playback_events (event_id, video_id, type, device_id, session_id, occurred_at) "
                    + "VALUES (?, ?, 'ad_impression', ?, 's', ?)",
                    UUID.randomUUID().toString(), adId, DEVICE, Timestamp.from(Instant.now()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String deviceId) {
        HttpHeaders headers = new HttpHeaders();
        if (deviceId != null) headers.set("X-Device-Id", deviceId);
        Map<String, Object> body = http.exchange("/v1/feed?limit=10", HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                .getBody();
        return (List<Map<String, Object>>) body.get("items");
    }

    @Test
    void oneSponsoredClipGoesAfterTheFifthClip() {
        seedVideos(10);
        sponsor("ad001");
        List<Map<String, Object>> items = items(DEVICE);
        assertThat(items).hasSize(11);
        assertThat(items.get(5)).containsEntry("id", "ad001")
                .containsEntry("sponsored", true)
                .containsEntry("cta_url", "https://example.com")
                .containsEntry("hls_url", "http://media.test/media/ad001/index.m3u8");
        assertThat(items.get(0)).containsEntry("sponsored", false);
    }

    @Test
    void noDeviceIdNoSponsoredClip() {
        seedVideos(10);
        sponsor("ad001");
        assertThat(items(null)).hasSize(10);
    }

    @Test
    void aShortPageGetsNone() {
        seedVideos(3);
        sponsor("ad001");
        assertThat(items(DEVICE)).hasSize(3);
    }

    @Test
    void theDailyCapStopsThem() {
        seedVideos(10);
        sponsor("ad001");
        impressions("ad001", 6);
        assertThat(items(DEVICE)).hasSize(10);
        assertThat(items("device-0002")).hasSize(11); // another device is unaffected
    }

    @Test
    void theCreativeSeenLeastIsChosen() {
        seedVideos(10);
        sponsor("ad001");
        sponsor("ad002");
        impressions("ad001", 2);
        assertThat(items(DEVICE).get(5)).containsEntry("id", "ad002");
    }

    @Test
    void sponsoredImpressionsAreAcceptedAsEvents() {
        String batch = """
                {"device_id":"device-0001","events":[{"event_id":"e-000001","video_id":"ad001",
                 "type":"ad_impression","session_id":"s1","occurred_at":"2026-11-22T10:00:00Z"},
                 {"event_id":"e-000002","video_id":"ad001","type":"ad_click","session_id":"s1",
                 "occurred_at":"2026-11-22T10:00:05Z"}]}""";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = http.postForObject("/v1/events", new HttpEntity<>(batch, headers), Map.class);
        assertThat(result).containsEntry("accepted", 2);
    }
}
