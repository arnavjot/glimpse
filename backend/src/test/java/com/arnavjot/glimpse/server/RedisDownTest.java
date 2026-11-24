package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis pointed at a port nothing listens on: the feed must still be served from
 * Postgres (Redis is only a cache), and quickly, because of the 500 ms timeouts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "glimpse.media-base-url=http://media.test/media",
        "glimpse.seed-manifest=does-not-exist.json",
        "spring.data.redis.url=redis://127.0.0.1:1"
})
class RedisDownTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", IntegrationTest.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", IntegrationTest.POSTGRES::getUsername);
        r.add("spring.datasource.password", IntegrationTest.POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void theFeedStillWorksFromPostgres() {
        jdbc.execute("TRUNCATE uploads, likes, playback_events, videos");
        jdbc.update("INSERT INTO videos (id, title, creator, duration_ms, width, height, hls_path, poster_path, "
                + "published_at) VALUES ('v1', 't', 'c', 1000, 720, 1280, 'a', 'b', ?)", Timestamp.from(Instant.now()));
        long start = System.nanoTime();
        Map<String, Object> page = http.getForObject("/v1/feed", Map.class);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat((List<Object>) page.get("items")).hasSize(1);
        assertThat(ms).isLessThan(3000);
        assertThat(http.getForObject("/actuator/prometheus", String.class))
                .contains("glimpse_feed_cache_total{result=\"unavailable\"");
    }

    @Test
    void signingUpStillWorksWhenRateLimitsCannotBeChecked() {
        String name = "u" + java.util.UUID.randomUUID().toString().substring(0, 8);
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        // Limits fail open: without Redis they can't be counted, so the request is allowed.
        var r = http.postForEntity("/v1/auth/register", new org.springframework.http.HttpEntity<>(
                "{\"email\":\"" + name + "@example.com\",\"password\":\"correct horse\",\"handle\":\"" + name + "\"}", h),
                Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(http.getForObject("/actuator/prometheus", String.class))
                .contains("glimpse_ratelimit_unavailable_total{limit=\"register-ip\"");
    }
}
