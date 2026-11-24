package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.arnavjot.glimpse.server.ranking.RankingJob;
import com.arnavjot.glimpse.server.rollups.RollupJob;

/** Hourly rollups, the "For You" ranking snapshots, and the A/B test around them. */
@TestPropertySource(properties = "glimpse.push.admin-token=test-secret")
@SuppressWarnings("unchecked") // JSON bodies read as plain maps
class RankingExperimentsApiTest extends IntegrationTest {

    private static final String ADMIN = "test-secret";

    @Autowired RollupJob rollups;
    @Autowired RankingJob ranking;

    /** An event that happened {@code agoSeconds} ago, arriving now. */
    private void event(String video, String type, Long valueMs, String device, int agoSeconds) {
        jdbc.update("""
                INSERT INTO playback_events (event_id, video_id, type, value_ms, preload, device_id, session_id, occurred_at)
                VALUES (?, ?, ?, ?, TRUE, ?, 's1', now() - make_interval(secs => ?))""",
                UUID.randomUUID().toString(), video, type, valueMs, device, agoSeconds);
    }

    /** {@code plays} plays of a clip, {@code completes} of them watched to the end. */
    private void plays(String video, int plays, int completes) {
        for (int i = 0; i < plays; i++) {
            event(video, "play_start", null, "viewer-" + i, 120);
            if (i < completes) event(video, "complete", null, "viewer-" + i, 120);
        }
    }

    private long stat(String video, String column) {
        Long v = jdbc.queryForObject("SELECT coalesce(sum(" + column + "), 0) FROM clip_stats_hourly WHERE video_id = ?",
                Long.class, video);
        return v == null ? 0 : v;
    }

    private ResponseEntity<Map> feed(String device, String cursor, int limit) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Device-Id", device);
        String url = "/v1/feed?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(ResponseEntity<Map> page) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) page.getBody().get("items")) out.add((String) v.get("id"));
        return out;
    }

    private void split(boolean enabled, int treatmentPercent) {
        jdbc.update("UPDATE experiments SET enabled = ?, treatment_percent = ?", enabled, treatmentPercent);
        experiments.clearCache();
    }

    private ResponseEntity<Map> admin(HttpMethod method, String path, String json) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Admin-Token", ADMIN);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(json, h), Map.class);
    }

    // --- Rollups ---

    @Test
    void eachEventIsCountedExactlyOnce() {
        seedVideos(1);
        plays("v000", 4, 3);
        event("v000", "watch", 5000L, "viewer-0", 120);
        rollups.runOnce(Duration.ZERO);
        rollups.runOnce(Duration.ZERO); // nothing new: must not count anything twice
        assertThat(stat("v000", "plays")).isEqualTo(4);
        assertThat(stat("v000", "completions")).isEqualTo(3);
        assertThat(stat("v000", "watch_ms")).isEqualTo(5000);

        event("v000", "play_start", null, "viewer-9", 0); // later traffic, added on top
        event("v000", "play_start", null, "viewer-8", 0);
        rollups.runOnce(Duration.ZERO);
        assertThat(stat("v000", "plays")).isEqualTo(6);
    }

    @Test
    void theLastMinuteWaitsForTheNextRun() {
        seedVideos(1);
        event("v000", "play_start", null, "viewer-1", 0); // may still have slower inserts racing it
        rollups.runOnce(); // the normal one-minute settle
        assertThat(stat("v000", "plays")).isZero();
        rollups.runOnce(Duration.ZERO); // once settled, it is counted
        assertThat(stat("v000", "plays")).isEqualTo(1);
    }

    @Test
    void hourlyStatsComeFromTheRollups() {
        seedVideos(1);
        plays("v000", 4, 2);
        event("v000", "first_frame", 300L, "viewer-0", 120);
        event("v000", "first_frame", 500L, "viewer-1", 120);
        rollups.runOnce(Duration.ZERO);
        List<?> hours = http.getForObject("/v1/videos/v000/stats/hourly", List.class);
        assertThat(hours).hasSize(1);
        assertThat((Map<String, Object>) hours.get(0)).containsEntry("plays", 4).containsEntry("completion_rate", 0.5)
                .containsEntry("avg_first_frame_ms", 400);
        assertThat(http.getForEntity("/v1/videos/nope/stats/hourly", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Ranking ---

    @Test
    void forYouPutsClipsPeopleFinishFirst() {
        seedVideos(3); // v000 newest
        plays("v000", 50, 5);  // people swipe away
        plays("v002", 50, 45); // people watch to the end
        rollups.runOnce(Duration.ZERO);
        ranking.runOnce();
        split(true, 100);
        ResponseEntity<Map> page = feed("device-treat-1", null, 10);
        assertThat(page.getHeaders().getFirst("X-Feed-Order")).isEqualTo("for-you");
        assertThat(ids(page)).containsExactly("v002", "v001", "v000");
    }

    @Test
    void aNewClipWithOnePlayIsNotRankedAsPerfect() {
        seedVideos(3);
        plays("v000", 1, 1);    // 1 of 1: "100%", but it means little
        plays("v001", 80, 64);  // 80%, from many plays
        plays("v002", 100, 30); // an average clip, so the site-wide rate is middling
        rollups.runOnce(Duration.ZERO);
        ranking.runOnce();
        split(true, 100);
        assertThat(ids(feed("device-treat-1", null, 10)).get(0)).isEqualTo("v001");
    }

    @Test
    void scrollingKeepsItsOrderWhenScoresChange() {
        seedVideos(6);
        for (int i = 0; i < 6; i++) plays(String.format("v%03d", i), 40, 5 + i * 6); // v005 best
        rollups.runOnce(Duration.ZERO);
        ranking.runOnce();
        split(true, 100);
        ResponseEntity<Map> first = feed("device-treat-1", null, 3);
        assertThat(ids(first)).containsExactly("v005", "v004", "v003");

        // Mid-scroll, the scores flip and a new snapshot is built.
        for (int i = 0; i < 6; i++) plays(String.format("v%03d", i), 200, 200 - i * 30);
        rollups.runOnce(Duration.ZERO);
        ranking.runOnce();
        ResponseEntity<Map> second = feed("device-treat-1", (String) first.getBody().get("next_cursor"), 3);
        // The cursor's snapshot is used: no repeats, nothing skipped.
        assertThat(ids(second)).containsExactly("v002", "v001", "v000");
        assertThat(second.getBody().get("next_cursor")).isNull();
        // A fresh scroll gets the new order.
        assertThat(ids(feed("device-treat-1", null, 3))).startsWith("v000");
    }

    @Test
    void aClipDeletedAfterTheSnapshotIsSkipped() {
        seedVideos(3);
        rollups.runOnce(Duration.ZERO);
        ranking.runOnce();
        jdbc.update("DELETE FROM videos WHERE id = 'v001'");
        split(true, 100);
        assertThat(ids(feed("device-treat-1", null, 10))).containsExactlyInAnyOrder("v000", "v002");
    }

    @Test
    void aBadRankedCursorIsRejected() {
        String bad = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("r|x|1".getBytes());
        assertThat(feed("device-treat-1", bad, 3).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- The experiment ---

    @Test
    void aDeviceKeepsItsVariantWhenTheSplitChanges() {
        seedVideos(2);
        ranking.runOnce();
        split(true, 100);
        assertThat(feed("device-early-1", null, 5).getHeaders().getFirst("X-Feed-Order")).isEqualTo("for-you");
        split(true, 0); // treatment closed to newcomers
        assertThat(feed("device-early-1", null, 5).getHeaders().getFirst("X-Feed-Order")).isEqualTo("for-you");
        assertThat(feed("device-late-01", null, 5).getHeaders().getFirst("X-Feed-Order")).isEqualTo("newest");
    }

    @Test
    void theKillSwitchServesNewestToEveryoneAndStopsCounting() {
        seedVideos(2);
        ranking.runOnce();
        split(true, 100);
        ResponseEntity<Map> before = feed("device-treat-1", null, 1);
        assertThat(before.getHeaders().getFirst("X-Feed-Order")).isEqualTo("for-you");

        assertThat(admin(HttpMethod.PUT, "/v1/admin/experiments/feed_ranking", "{\"enabled\":false}").getBody())
                .containsEntry("enabled", false);
        assertThat(feed("device-treat-1", null, 5).getHeaders().getFirst("X-Feed-Order")).isEqualTo("newest");
        assertThat(feed("device-other-1", null, 5).getHeaders().getFirst("X-Feed-Order")).isEqualTo("newest");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM experiment_exposures", Integer.class)).isEqualTo(1);
        // A scroll that started on "For You" finishes there instead of breaking.
        ResponseEntity<Map> next = feed("device-treat-1", (String) before.getBody().get("next_cursor"), 5);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(next)).hasSize(1).doesNotContainAnyElementsOf(ids(before));
    }

    @Test
    void theSplitIsRoughlyWhatWasAskedFor() {
        seedVideos(1);
        ranking.runOnce();
        int treated = 0;
        for (int i = 0; i < 400; i++) {
            if ("for-you".equals(feed("split-device-" + i, null, 1).getHeaders().getFirst("X-Feed-Order"))) treated++;
        }
        assertThat(treated).isBetween(150, 250); // 50% of 400, give or take
    }

    @Test
    void theReportFindsARealDifference() {
        seedVideos(1);
        for (int i = 0; i < 40; i++) {
            jdbc.update("INSERT INTO experiment_exposures (experiment_key, device_id, variant, first_exposed) "
                    + "VALUES ('feed_ranking', ?, 'control', now() - interval '1 hour')", "c-" + i);
            jdbc.update("INSERT INTO experiment_exposures (experiment_key, device_id, variant, first_exposed) "
                    + "VALUES ('feed_ranking', ?, 'treatment', now() - interval '1 hour')", "t-" + i);
            event("v000", "play_start", null, "c-" + i, 60);
            event("v000", "watch", 4000L + (i % 5) * 200, "c-" + i, 60);
            event("v000", "play_start", null, "t-" + i, 60);
            event("v000", "watch", 6000L + (i % 5) * 200, "t-" + i, 60);
            if (i % 2 == 0) event("v000", "complete", null, "t-" + i, 60);
        }
        event("v000", "watch", 99_000L, "c-0", 7200); // before exposure: ignored
        Map<String, Object> report = admin(HttpMethod.GET, "/v1/admin/experiments/feed_ranking/report", null).getBody();
        assertThat((Map<String, Object>) report.get("control")).containsEntry("devices", 40).containsEntry("watch_ms_per_device", 4400.0);
        assertThat((Map<String, Object>) report.get("treatment")).containsEntry("completion_rate", 0.5);
        Map<String, Object> watch = (Map<String, Object>) report.get("watch_ms_per_device");
        assertThat(watch).containsEntry("significant", true);
        assertThat(((Number) watch.get("lift")).doubleValue()).isCloseTo(0.4545, org.assertj.core.data.Offset.offset(0.001));
        assertThat(report.get("verdict")).isEqualTo("treatment wins on watch time");
    }

    @Test
    void aSmallExperimentDoesNotDeclareAWinner() {
        seedVideos(1);
        ranking.runOnce();
        split(true, 50);
        feed("device-small-1", null, 1);
        Map<String, Object> report = admin(HttpMethod.GET, "/v1/admin/experiments/feed_ranking/report", null).getBody();
        assertThat((String) report.get("verdict")).startsWith("not enough data");
    }

    @Test
    void experimentAdminNeedsTheToken() {
        assertThat(http.getForEntity("/v1/admin/experiments", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin(HttpMethod.PUT, "/v1/admin/experiments/nope", "{\"enabled\":false}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(admin(HttpMethod.PUT, "/v1/admin/experiments/feed_ranking", "{\"treatment_percent\":101}")
                .getStatusCode().is4xxClientError()).isTrue();
    }
}
