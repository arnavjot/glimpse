package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** GET /v1/videos/{id} and the share page /v/{id}. */
class ShareApiTest extends IntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void oneVideoCanBeFetchedById() {
        seedVideos(3);
        Map<String, Object> video = http.getForObject("/v1/videos/v001", Map.class);
        assertThat(video).containsEntry("id", "v001")
                .containsEntry("title", "Clip 1")
                .containsEntry("hls_url", "http://media.test/media/v001/index.m3u8");
    }

    @Test
    void anUnknownVideoIsA404() {
        ResponseEntity<String> r = http.getForEntity("/v1/videos/nope", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aMalformedIdIsA400() {
        ResponseEntity<String> r = http.getForEntity("/v1/videos/bad.id!", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theSharePageHasPreviewTagsAndADeepLink() {
        seedVideos(1);
        ResponseEntity<String> r = http.getForEntity("/v/v000", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(r.getBody())
                .contains("<meta property=\"og:title\" content=\"Clip 0\">")
                .contains("http://media.test/media/v000/poster.jpg")
                .contains("href=\"glimpse://video/v000\"");
    }

    @Test
    void theSharePageFallsBackToThePlayStoreWithTheClipInTheReferrer() {
        seedVideos(1);
        String body = http.getForObject("/v/v000", String.class);
        // Android: the intent URL opens the app if installed, else the Play Store...
        assertThat(body).contains("data-android=\"intent://video/v000#Intent;scheme=glimpse;"
                + "package=com.arnavjot.glimpse;S.browser_fallback_url=");
        // ...whose referrer carries the clip, for the first launch after install.
        assertThat(body).contains("https://play.google.com/store/apps/details?id=com.arnavjot.glimpse"
                + "&amp;referrer=utm_source%3Dshare%26utm_medium%3Dlink%26video_id%3Dv000");
    }

    @Test
    void assetLinksIsOffUntilFingerprintsAreConfigured() {
        assertThat(http.getForEntity("/.well-known/assetlinks.json", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theSharePageEscapesTitles() {
        seedVideos(1);
        jdbc.update("UPDATE videos SET title = '<script>alert(1)</script>' WHERE id = 'v000'");
        String body = http.getForObject("/v/v000", String.class);
        assertThat(body).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
    }

    @Test
    void anUnknownSharePageIsA404() {
        ResponseEntity<String> r = http.getForEntity("/v/nope", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private List<String> firstPageIds(String query) {
        Map<String, Object> body = http.getForObject("/v1/feed" + query, Map.class);
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) body.get("items")) ids.add((String) v.get("id"));
        return ids;
    }

    @Test
    void aSharedClipComesFirstWithoutADuplicate() {
        seedVideos(5);
        assertThat(firstPageIds("?limit=3&first=v001")).containsExactly("v001", "v000", "v002");
    }

    @Test
    void aSharedClipFromAnyPageIsMovedToTheTop() {
        seedVideos(5);
        assertThat(firstPageIds("?limit=2&first=v004")).containsExactly("v004", "v000", "v001");
    }

    @Test
    void anUnknownFirstIdLeavesTheFeedAsItIs() {
        seedVideos(3);
        assertThat(firstPageIds("?limit=3&first=gone")).containsExactly("v000", "v001", "v002");
    }

    @Test
    void theFeedCacheIsSharedWithOrdinaryRequests() {
        seedVideos(3);
        firstPageIds("?limit=3&first=v002");
        assertThat(redis.hasKey("feed:first:3")).isTrue();
        assertThat(firstPageIds("?limit=3")).containsExactly("v000", "v001", "v002");
    }

    @Test
    void badQueryParametersAre400sNot500s() {
        assertThat(http.getForEntity("/v1/feed?first=bad.id", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> zero = http.getForEntity("/v1/feed?limit=0", String.class);
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(zero.getBody()).contains("limit");
    }
}
