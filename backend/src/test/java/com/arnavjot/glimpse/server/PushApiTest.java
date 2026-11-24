package com.arnavjot.glimpse.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.arnavjot.glimpse.server.feed.FeedPage;
import com.arnavjot.glimpse.server.push.PushSender;

/** The admin push endpoint, with Firebase replaced by a mock. */
@TestPropertySource(properties = "glimpse.push.admin-token=test-secret")
class PushApiTest extends IntegrationTest {

    @MockitoBean PushSender sender;

    @BeforeEach
    void pushIsOn() throws Exception {
        when(sender.enabled()).thenReturn(true);
        when(sender.sendNewClip(any())).thenReturn("projects/p/messages/1");
    }

    private ResponseEntity<String> push(String videoId, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.set("X-Admin-Token", token);
        return http.postForEntity("/v1/admin/push/new-clip/" + videoId, new HttpEntity<>(headers), String.class);
    }

    @Test
    void anAdminCanAnnounceAClip() throws Exception {
        seedVideos(2);
        ResponseEntity<String> r = push("v001", "test-secret");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).contains("projects/p/messages/1");
        ArgumentCaptor<FeedPage.Video> sent = ArgumentCaptor.forClass(FeedPage.Video.class);
        verify(sender).sendNewClip(sent.capture());
        assertThat(sent.getValue().id()).isEqualTo("v001");
        assertThat(sent.getValue().posterUrl()).isEqualTo("http://media.test/media/v001/poster.jpg");
    }

    @Test
    void aMissingOrWrongTokenIsRefused() {
        seedVideos(1);
        assertThat(push("v000", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(push("v000", "guess").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnknownClipIsA404() {
        assertThat(push("nope", "test-secret").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withoutFirebaseItIs503() {
        seedVideos(1);
        when(sender.enabled()).thenReturn(false);
        assertThat(push("v000", "test-secret").getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
