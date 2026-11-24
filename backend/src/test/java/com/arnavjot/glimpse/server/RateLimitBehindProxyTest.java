package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * Behind nginx: the test client plays the trusted proxy, so X-Forwarded-For names the real
 * client. Without this, everyone behind the proxy would share one allowance and one busy
 * client would lock everybody out.
 */
@TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1")
class RateLimitBehindProxyTest extends IntegrationTest {

    private ResponseEntity<Map> loginFrom(String ip, int n) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Forwarded-For", ip);
        return http.postForEntity("/v1/auth/login",
                new HttpEntity<>("{\"email\":\"person" + n + "@example.com\",\"password\":\"wrong pass 1\"}", h), Map.class);
    }

    @Test
    void eachClientBehindTheProxyHasItsOwnAllowance() {
        for (int i = 0; i < 20; i++) loginFrom("203.0.113.1", i);
        assertThat(loginFrom("203.0.113.1", 99).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Another person behind the same nginx is not affected.
        assertThat(loginFrom("198.51.100.7", 99).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(redis.keys("rl:login-ip:*")).containsExactlyInAnyOrder(
                "rl:login-ip:203.0.113.1", "rl:login-ip:198.51.100.7");
    }
}
