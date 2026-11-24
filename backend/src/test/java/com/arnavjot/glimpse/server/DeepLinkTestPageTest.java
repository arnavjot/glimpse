package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** The /dev/deeplink-test page, with developer pages turned on. */
@TestPropertySource(properties = "glimpse.dev-tools=true")
class DeepLinkTestPageTest extends IntegrationTest {

    @Test
    void defaultsToASpotifySongWithAPlayStoreFallback() {
        String body = http.getForObject("/dev/deeplink-test", String.class);
        assertThat(body)
                .contains("intent:track:5rVhh5aM1xyVe6JUXPTte3#Intent;scheme=spotify;package=com.spotify.music;"
                        + "S.browser_fallback_url=https%3A%2F%2Fplay.google.com%2Fstore%2Fapps%2Fdetails%3Fid%3Dcom.spotify.music")
                .contains("https://play.google.com/store/apps/details?id=com.spotify.music&amp;referrer=");
    }

    @Test
    void anyAppWithoutADeepLinkOpensItsHomeScreen() {
        String body = http.getForObject("/dev/deeplink-test?package=com.example.app", String.class);
        assertThat(body).contains("intent:#Intent;action=android.intent.action.MAIN;"
                + "category=android.intent.category.LAUNCHER;package=com.example.app;");
    }

    @Test
    void rejectsInvalidInput() {
        ResponseEntity<String> badPackage = http.getForEntity("/dev/deeplink-test?package=x\"><script>", String.class);
        assertThat(badPackage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> badUri = http.getForEntity("/dev/deeplink-test?uri=javascript alert(1)", String.class);
        assertThat(badUri.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
