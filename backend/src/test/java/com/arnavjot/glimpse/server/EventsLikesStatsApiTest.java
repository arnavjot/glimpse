package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class EventsLikesStatsApiTest extends IntegrationTest {

    static final String DEVICE = "device-abc123";

    static Map<String, Object> event(String id, String type, Long valueMs, Boolean preload) {
        Map<String, Object> e = new HashMap<>();
        e.put("event_id", id);
        e.put("video_id", "v000");
        e.put("type", type);
        e.put("value_ms", valueMs);
        e.put("preload", preload);
        e.put("session_id", "session-1");
        e.put("occurred_at", "2026-11-21T10:00:00+05:30");
        return e;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> post(List<Map<String, Object>> events) {
        return http.postForEntity("/v1/events", Map.of("device_id", DEVICE, "events", events), Map.class);
    }

    @Test
    void aRetriedBatchIsStoredOnce() {
        seedVideos(1);
        List<Map<String, Object>> batch = List.of(
                event("e-00000001", "play_start", null, true),
                event("e-00000002", "play_start", null, true),
                event("e-00000003", "play_start", null, true));
        assertThat(post(batch).getBody()).isEqualTo(Map.of("accepted", 3, "duplicates", 0));
        // The app resends a batch whose response it never saw: nothing is stored twice.
        assertThat(post(batch).getBody()).isEqualTo(Map.of("accepted", 0, "duplicates", 3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM playback_events", Long.class)).isEqualTo(3);
    }

    @Test
    void anIdRepeatedInsideOneBatchCountsOnce() {
        Map<String, Object> e = event("e-00000001", "play_start", null, true);
        assertThat(post(List.of(e, e)).getBody()).isEqualTo(Map.of("accepted", 1, "duplicates", 1));
    }

    @Test
    void eventsNeedATimezoneAndAKnownType() {
        Map<String, Object> naive = event("e-00000001", "play_start", null, true);
        naive.put("occurred_at", "2026-11-21T10:00:00");
        assertThat(post(List.of(naive)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post(List.of(event("e-00000002", "teleport", null, true))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void oversizedBatchesAreRejected() {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 501; i++) batch.add(event(String.format("e-%08d", i), "play_start", null, true));
        assertThat(post(batch).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void likesAreIdempotent() {
        seedVideos(1);
        for (int i = 0; i < 2; i++) {
            Map body = http.exchange("/v1/videos/v000/like", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("device_id", DEVICE)), Map.class).getBody();
            assertThat(body).containsEntry("liked", true).containsEntry("like_count", 1);
        }
        for (int i = 0; i < 2; i++) {
            Map body = http.exchange("/v1/videos/v000/like?device_id=" + DEVICE, HttpMethod.DELETE,
                    null, Map.class).getBody();
            assertThat(body).containsEntry("liked", false).containsEntry("like_count", 0);
        }
    }

    @Test
    void likingAnUnknownVideoIsA404() {
        ResponseEntity<String> r = http.exchange("/v1/videos/nope/like", HttpMethod.PUT,
                new HttpEntity<>(Map.of("device_id", DEVICE)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    void playbackStatsSplitFirstFrameByPreload() {
        seedVideos(1);
        List<Map<String, Object>> events = new ArrayList<>();
        long[] warm = {40, 60, 80, 100};
        long[] cold = {400, 600, 900};
        for (int i = 0; i < warm.length; i++) events.add(event("w-" + 1000000 + i, "first_frame", warm[i], true));
        for (int i = 0; i < cold.length; i++) events.add(event("c-" + 1000000 + i, "first_frame", cold[i], false));
        events.add(event("s-10000001", "play_start", null, true));
        events.add(event("s-10000002", "play_start", null, true));
        events.add(event("r-10000001", "rebuffer", null, true));
        post(events);
        Map<String, Object> stats = http.getForObject("/v1/stats/playback", Map.class);
        assertThat(stats.get("first_frame_preloaded")).isEqualTo(Map.of("count", 4, "p50_ms", 60, "p95_ms", 100));
        assertThat(stats.get("first_frame_not_preloaded")).isEqualTo(Map.of("count", 3, "p50_ms", 600, "p95_ms", 900));
        assertThat(stats).containsEntry("plays", 2).containsEntry("rebuffers_per_play", 0.5);
    }
}
