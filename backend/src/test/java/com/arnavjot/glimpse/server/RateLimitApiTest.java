package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Per-IP limits on sign-in, sign-up and refresh; per-account limits on uploads. Here the
 * test client is not a trusted proxy, so its X-Forwarded-For must be ignored (see
 * {@link RateLimitBehindProxyTest} for the nginx case).
 */
class RateLimitApiTest extends IntegrationTest {

    private ResponseEntity<Map> post(String path, String json, HttpHeaders extra) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (extra != null) h.addAll(extra);
        return http.postForEntity(path, new HttpEntity<>(json, h), Map.class);
    }

    private ResponseEntity<Map> login(String email, HttpHeaders extra) {
        return post("/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong pass 1\"}", extra);
    }

    private ResponseEntity<Map> register(String name) {
        return post("/v1/auth/register", "{\"email\":\"" + name + "@example.com\",\"password\":\"correct horse\","
                + "\"handle\":\"" + name + "\"}", null);
    }

    private ResponseEntity<Map> createUpload(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/v1/uploads",
                new HttpEntity<>("{\"title\":\"clip\",\"content_type\":\"video/mp4\",\"size_bytes\":1000}", h), Map.class);
    }

    private static HttpHeaders forwardedFor(String ip) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        return h;
    }

    @Test
    void oneMachineCannotTryManyAccounts() {
        // 20 different emails: the per-email limit never triggers, the per-IP one does.
        for (int i = 0; i < 20; i++) {
            assertThat(login("person" + i + "@example.com", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map> refused = login("person20@example.com", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        assertThat((String) refused.getBody().get("detail")).startsWith("too many sign-in attempts from your network");
    }

    @Test
    void aForgedForwardedForHeaderDoesNotGetAFreshAllowance() {
        // Not from the trusted proxy: the header is ignored and the real address is charged.
        // (Checked on the bucket itself, so the test doesn't depend on how fast it runs.)
        login("someone@example.com", forwardedFor("203.0.113.77"));
        login("someone@example.com", forwardedFor("198.51.100.1"));
        Set<String> keys = redis.keys("rl:login-ip:*");
        assertThat(keys).hasSize(1).noneMatch(k -> k.contains("203.0.113.77") || k.contains("198.51.100.1"));
        assertThat(keys.iterator().next()).matches("rl:login-ip:(127\\.0\\.0\\.1|0:0:0:0:0:0:0:1)");
    }

    @Test
    void theAllowanceRefillsOverTime() {
        for (int i = 0; i < 21; i++) login("person" + i + "@example.com", null);
        assertThat(login("again@example.com", null).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // As if the 10 minutes had passed: the bucket's last update moved back in time.
        Set<String> keys = redis.keys("rl:login-ip:*");
        assertThat(keys).hasSize(1);
        redis.opsForHash().put(keys.iterator().next(), "ts", "0");
        assertThat(login("again@example.com", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void signUpsArePerAddressToo() {
        for (int i = 0; i < 5; i++) assertThat(register("maker" + i).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> sixth = register("maker5");
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(5);
    }

    @Test
    void anAccountCanHaveOnlyThreeUploadsInProgress() {
        String token = (String) register("maker").getBody().get("access_token");
        for (int i = 0; i < 3; i++) assertThat(createUpload(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> fourth = createUpload(token);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat((String) fourth.getBody().get("detail")).contains("3 uploads in progress");

        // One finished processing: a slot is free again.
        jdbc.update("UPDATE uploads SET status = 'ready' WHERE id = (SELECT id FROM uploads LIMIT 1)");
        assertThat(createUpload(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createUpload(token).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void anAbandonedUploadDoesNotHoldASlot() {
        String token = (String) register("maker").getBody().get("access_token");
        for (int i = 0; i < 3; i++) createUpload(token);
        // The app crashed before sending the files; their upload URLs have expired.
        jdbc.update("UPDATE uploads SET created_at = now() - interval '20 minutes'");
        assertThat(createUpload(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anAccountCanStartTenUploadsAnHour() {
        String token = (String) register("maker").getBody().get("access_token");
        for (int i = 0; i < 10; i++) {
            assertThat(createUpload(token).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            jdbc.update("UPDATE uploads SET status = 'ready'"); // each one done, so the cap isn't what stops us
        }
        ResponseEntity<Map> eleventh = createUpload(token);
        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat((String) eleventh.getBody().get("detail")).startsWith("too many uploads");
        assertThat(Long.parseLong(eleventh.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(60L, 400L);
        // Limits are per account: someone else can still post.
        String other = (String) register("other").getBody().get("access_token");
        assertThat(createUpload(other).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
