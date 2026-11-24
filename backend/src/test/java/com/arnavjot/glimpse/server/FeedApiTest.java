package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FeedApiTest extends IntegrationTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> page(String query) {
        return http.getForObject("/v1/feed" + query, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagesCoverEveryVideoOnceNewestFirst() {
        seedVideos(25);
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Map<String, Object> body = page("?limit=10" + (cursor == null ? "" : "&cursor=" + cursor));
            for (Map<String, Object> v : (List<Map<String, Object>>) body.get("items")) seen.add((String) v.get("id"));
            cursor = (String) body.get("next_cursor");
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) expected.add(String.format("v%03d", i));
        assertThat(seen).isEqualTo(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void itemsCarryPlayableUrlsInSnakeCase() {
        seedVideos(1);
        Map<String, Object> item = ((List<Map<String, Object>>) page("").get("items")).get(0);
        assertThat(item).containsEntry("hls_url", "http://media.test/media/v000/index.m3u8")
                .containsEntry("poster_url", "http://media.test/media/v000/poster.jpg")
                .containsEntry("duration_ms", 12000)
                .containsEntry("like_count", 0);
    }

    @Test
    void theSecondRequestIsServedFromRedis() {
        seedVideos(5);
        Map<String, Object> first = page("");
        assertThat(redis.hasKey("feed:first:10")).isTrue();
        // Change the database behind the cache: the cached page is still served.
        jdbc.update("UPDATE videos SET title = 'changed'");
        assertThat(page("")).isEqualTo(first);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageSizeIsCapped() {
        seedVideos(60);
        assertThat((List<Object>) page("?limit=500").get("items")).hasSize(50);
    }

    @Test
    void aBadCursorIsA400() {
        ResponseEntity<String> r = http.getForEntity("/v1/feed?cursor=not-a-cursor", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void readinessReportsUp() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health/readiness", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("UP");
    }

    @Test
    void metricsAreExposedForPrometheus() {
        seedVideos(1);
        page("");
        String metrics = http.getForObject("/actuator/prometheus", String.class);
        assertThat(metrics).contains("glimpse_feed_cache_total").contains("http_server_requests_seconds_bucket");
    }
}
