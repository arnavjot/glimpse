package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Feature flags, remote config and the kill switch, end to end. */
@TestPropertySource(properties = "glimpse.push.admin-token=test-secret")
class RemoteConfigApiTest extends IntegrationTest {

    private static final String DEVICE = "device-0001";

    private HttpHeaders headers(String deviceId, String adminToken) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (deviceId != null) h.set("X-Device-Id", deviceId);
        if (adminToken != null) h.set("X-Admin-Token", adminToken);
        return h;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> config(String deviceId) {
        return http.exchange("/v1/config", HttpMethod.GET, new HttpEntity<>(headers(deviceId, null)), Map.class)
                .getBody();
    }

    @SuppressWarnings("unchecked")
    private boolean flag(String deviceId, String key) {
        return (Boolean) ((Map<String, Object>) config(deviceId).get("flags")).get(key);
    }

    private ResponseEntity<String> admin(HttpMethod method, String path, String json, String token) {
        return http.exchange(path, method, new HttpEntity<>(json, headers(null, token)), String.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void devicesGetTheSeededFlagsAndValues() {
        Map<String, Object> body = config(DEVICE);
        Map<String, Object> flags = (Map<String, Object>) body.get("flags");
        assertThat(flags).containsEntry("sponsored_clips", true).containsEntry("preload", true)
                .containsEntry("share_cards", true).containsEntry("push_notifications", true);
        assertThat((Map<String, Object>) body.get("values")).containsEntry("sponsored_slot", "5");
        assertThat(body).containsEntry("refresh_seconds", 300);
    }

    @Test
    void unchangedConfigIsA304() {
        ResponseEntity<String> first = http.exchange("/v1/config", HttpMethod.GET,
                new HttpEntity<>(headers(DEVICE, null)), String.class);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        HttpHeaders again = headers(DEVICE, null);
        again.setIfNoneMatch(etag);
        ResponseEntity<String> second = http.exchange("/v1/config", HttpMethod.GET, new HttpEntity<>(again), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
    }

    @Test
    void theKillSwitchTurnsAFeatureOffForEveryone() {
        seedVideos(10);
        jdbc.update("INSERT INTO sponsored_clips (id, title, advertiser, duration_ms, width, height, hls_path, "
                + "poster_path, cta_url) VALUES ('ad001', 'Ad', 'Glimpse', 10000, 720, 1280, 'a/i.m3u8', 'a/p.jpg', "
                + "'https://example.com')");
        assertThat(feedSize()).isEqualTo(11); // the sponsored clip is there

        ResponseEntity<String> kill = admin(HttpMethod.POST, "/v1/admin/config/sponsored_clips/kill", null, "test-secret");
        assertThat(kill.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kill.getBody()).contains("\"enabled\":false");

        assertThat(flag(DEVICE, "sponsored_clips")).isFalse();
        assertThat(flag("device-0002", "sponsored_clips")).isFalse();
        assertThat(feedSize()).isEqualTo(10); // and the server stopped inserting it
    }

    @SuppressWarnings("unchecked")
    private int feedSize() {
        Map<String, Object> body = http.exchange("/v1/feed?limit=10", HttpMethod.GET,
                new HttpEntity<>(headers(DEVICE, null)), Map.class).getBody();
        return ((List<Object>) body.get("items")).size();
    }

    @Test
    void killingPushBlocksTheAdminBroadcast() {
        seedVideos(1);
        admin(HttpMethod.POST, "/v1/admin/config/push_notifications/kill", null, "test-secret");
        ResponseEntity<String> push = admin(HttpMethod.POST, "/v1/admin/push/new-clip/v000", null, "test-secret");
        assertThat(push.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(push.getBody()).contains("switched off");
    }

    @Test
    void aRolloutReachesAStableShareOfDevices() {
        admin(HttpMethod.PUT, "/v1/admin/config/preload", "{\"rollout_percent\":50}", "test-secret");
        int on = 0;
        for (int i = 0; i < 200; i++) {
            String device = "device-" + i + "-abcdef";
            boolean first = flag(device, "preload");
            assertThat(flag(device, "preload")).isEqualTo(first); // the same device, the same answer
            if (first) on++;
        }
        assertThat(on).isBetween(70, 130); // about half of 200

        admin(HttpMethod.PUT, "/v1/admin/config/preload", "{\"rollout_percent\":0}", "test-secret");
        assertThat(flag(DEVICE, "preload")).isFalse();
    }

    @Test
    void aRemoteValueChangesServerBehaviour() {
        seedVideos(10);
        jdbc.update("INSERT INTO sponsored_clips (id, title, advertiser, duration_ms, width, height, hls_path, "
                + "poster_path, cta_url) VALUES ('ad001', 'Ad', 'Glimpse', 10000, 720, 1280, 'a/i.m3u8', 'a/p.jpg', "
                + "'https://example.com')");
        admin(HttpMethod.PUT, "/v1/admin/config/sponsored_slot", "{\"value\":\"2\"}", "test-secret");
        Map<?, ?> body = http.exchange("/v1/feed?limit=10", HttpMethod.GET,
                new HttpEntity<>(headers(DEVICE, null)), Map.class).getBody();
        assertThat(((Map<?, ?>) ((List<?>) body.get("items")).get(2)).get("id")).isEqualTo("ad001");
    }

    @Test
    void adminEndpointsNeedTheToken() {
        assertThat(admin(HttpMethod.POST, "/v1/admin/config/preload/kill", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin(HttpMethod.GET, "/v1/admin/config", null, "wrong").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(admin(HttpMethod.GET, "/v1/admin/config", null, "test-secret").getBody()).contains("sponsored_slot");
    }

    @Test
    void badUpdatesAreRejected() {
        assertThat(admin(HttpMethod.PUT, "/v1/admin/config/preload", "{\"rollout_percent\":150}", "test-secret")
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(admin(HttpMethod.PUT, "/v1/admin/config/nope", "{\"enabled\":true}", "test-secret")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
