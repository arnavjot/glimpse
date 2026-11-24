package com.arnavjot.glimpse.server.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.arnavjot.glimpse.server.sponsored.SponsoredProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads the demo library from media/manifest.json (written by
 * scripts/make_media.sh) the first time the server starts with an empty
 * videos table. Newer clips get newer publish times, so v001 tops the feed.
 */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    record Clip(String id, String title, String creator, int durationMs, int width, int height,
                String hlsPath, String posterPath) {}

    record Ad(String id, String title, String advertiser, int durationMs, int width, int height,
              String hlsPath, String posterPath, String ctaUrl) {}

    private final JdbcTemplate jdbc;
    private final GlimpseProperties props;
    private final SponsoredProperties sponsored;
    private final ObjectMapper json;

    public SeedRunner(JdbcTemplate jdbc, GlimpseProperties props, SponsoredProperties sponsored, ObjectMapper json) {
        this.jdbc = jdbc;
        this.props = props;
        this.sponsored = sponsored;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        seedVideos();
        seedSponsored();
    }

    /** Sponsored creatives from media/sponsored.json, the first time the table is empty. */
    private void seedSponsored() throws IOException {
        if (sponsored.seedManifest() == null || sponsored.seedManifest().isBlank()) return;
        Path manifest = Path.of(sponsored.seedManifest());
        Long existing = jdbc.queryForObject("SELECT count(*) FROM sponsored_clips", Long.class);
        if ((existing != null && existing > 0) || !Files.exists(manifest)) return;
        List<Ad> ads = json.readValue(manifest.toFile(), new TypeReference<>() {});
        for (Ad a : ads) {
            jdbc.update("INSERT INTO sponsored_clips (id, title, advertiser, duration_ms, width, height, hls_path, "
                            + "poster_path, cta_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    a.id(), a.title(), a.advertiser(), a.durationMs(), a.width(), a.height(), a.hlsPath(),
                    a.posterPath(), a.ctaUrl());
        }
        log.info("Seeded {} sponsored clips from {}", ads.size(), manifest);
    }

    private void seedVideos() throws IOException {
        Path manifest = Path.of(props.seedManifest());
        Long existing = jdbc.queryForObject("SELECT count(*) FROM videos", Long.class);
        if (existing != null && existing > 0) return;
        if (!Files.exists(manifest)) {
            log.warn("No videos and no {} to seed from: run scripts/make_media.sh", manifest);
            return;
        }
        List<Clip> clips = json.readValue(manifest.toFile(), new TypeReference<>() {});
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            jdbc.update("INSERT INTO videos (id, title, creator, duration_ms, width, height, hls_path, "
                            + "poster_path, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                    c.id(), c.title(), c.creator(), c.durationMs(), c.width(), c.height(), c.hlsPath(),
                    c.posterPath(), Timestamp.from(now.minus(i * 7L, ChronoUnit.MINUTES)));
        }
        log.info("Seeded {} videos from {}", clips.size(), manifest);
    }
}
