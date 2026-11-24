package com.arnavjot.glimpse.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.arnavjot.glimpse.server.config.RemoteConfigService;

/**
 * Base for tests against real Postgres, Redis and S3-compatible storage (SeaweedFS),
 * started once in Docker by Testcontainers and shared by every test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "glimpse.media-base-url=http://media.test/media",
        "glimpse.seed-manifest=does-not-exist.json",
        // Spring caches one application per distinct test configuration, each with its own
        // pool. At the production size (20) five of them exhaust Postgres's 100 connections.
        "spring.datasource.hikari.maximum-pool-size=4"
})
public abstract class IntegrationTest {

    static final String S3_CONFIG = "{\"identities\":[{\"name\":\"glimpse\",\"credentials\":[{\"accessKey\":"
            + "\"glimpse\",\"secretKey\":\"test-secret\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\",\"Tagging\"]}]}";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    /** S3-compatible storage for uploads: the same SeaweedFS image as docker-compose. */
    static final GenericContainer<?> S3 = new GenericContainer<>("chrislusf/seaweedfs:4.53")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withExposedPorts(8333)
            // 403: the S3 API is up and asking for credentials.
            .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403).withStartupTimeout(Duration.ofMinutes(2)));

    /** Where the upload worker writes finished HLS in tests. */
    protected static final Path MEDIA_DIR = createMediaDir();

    static {
        POSTGRES.start();
        REDIS.start();
        S3.start();
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("glimpse-media-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static String s3Endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(8333);
    }

    @DynamicPropertySource
    static void datasources(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        r.add("glimpse.storage.endpoint", IntegrationTest::s3Endpoint);
        r.add("glimpse.storage.public-endpoint", IntegrationTest::s3Endpoint);
        r.add("glimpse.storage.secret-key", () -> "test-secret");
        r.add("glimpse.worker.media-dir", MEDIA_DIR::toString);
    }

    @Autowired protected TestRestTemplate http;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected RemoteConfigService remoteConfig;
    @Autowired protected com.arnavjot.glimpse.server.experiments.ExperimentService experiments;

    /**
     * The test client shows each response as the server sent it. Apache HttpClient's default
     * retries a 429 or 503 after sleeping for its Retry-After, which would hide rate limits
     * (and sleep for up to an hour); redirects and cookies stay off as in TestRestTemplate.
     */
    private static final HttpComponentsClientHttpRequestFactory AS_SENT = new HttpComponentsClientHttpRequestFactory(
            HttpClients.custom().disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().build());

    @BeforeEach
    void cleanSlate() {
        http.getRestTemplate().setRequestFactory(AS_SENT);
        jdbc.execute("TRUNCATE uploads, likes, playback_events, videos, sponsored_clips, refresh_tokens, users, "
                + "clip_stats_hourly, ranking_snapshots, ranked_clips, experiment_exposures");
        jdbc.update("UPDATE rollup_state SET processed_upto = '-infinity'");
        // The ranking experiment as seeded. With no ranking snapshot, "For You" serves newest first.
        jdbc.update("UPDATE experiments SET enabled = TRUE, treatment_percent = 50");
        experiments.clearCache();
        // Flags back to their seeded state: a kill switch in one test must not leak into the next.
        jdbc.update("UPDATE remote_config SET enabled = TRUE, rollout_percent = 100");
        jdbc.update("UPDATE remote_config SET value = '5' WHERE key = 'sponsored_slot'");
        remoteConfig.invalidate();
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /** n videos; v000 is the newest. */
    protected void seedVideos(int n) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < n; i++) {
            String id = String.format("v%03d", i);
            jdbc.update("INSERT INTO videos (id, title, creator, duration_ms, width, height, hls_path, "
                            + "poster_path, published_at) VALUES (?, ?, 'tester', 12000, 720, 1280, ?, ?, ?)",
                    id, "Clip " + i, id + "/index.m3u8", id + "/poster.jpg",
                    Timestamp.from(now.minus(i, ChronoUnit.MINUTES)));
        }
    }
}
