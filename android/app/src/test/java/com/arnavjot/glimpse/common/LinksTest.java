package com.arnavjot.glimpse.common;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class LinksTest {

    @Test
    public void readsTheClipIdFromADeepLink() {
        assertThat(Links.videoIdFromDeepLink("glimpse://video/v001")).isEqualTo("v001");
        assertThat(Links.videoIdFromDeepLink("glimpse://video/v001/")).isEqualTo("v001");
        assertThat(Links.videoIdFromDeepLink("GLIMPSE://VIDEO/v_1-a")).isEqualTo("v_1-a");
    }

    @Test
    public void rejectsAnythingElse() {
        assertThat(Links.videoIdFromDeepLink(null)).isNull();
        assertThat(Links.videoIdFromDeepLink("")).isNull();
        assertThat(Links.videoIdFromDeepLink("glimpse://video/")).isNull();
        assertThat(Links.videoIdFromDeepLink("glimpse://user/v001")).isNull();
        assertThat(Links.videoIdFromDeepLink("https://video/v001")).isNull();
        assertThat(Links.videoIdFromDeepLink("glimpse://video/a/b")).isNull();
        assertThat(Links.videoIdFromDeepLink("glimpse://video/..%2Fsecret")).isNull();
        assertThat(Links.videoIdFromDeepLink("not a uri")).isNull();
    }

    @Test
    public void readsTheClipIdFromAnAppLink() {
        assertThat(Links.videoIdFromDeepLink("https://glimpse.example.com/v/s001")).isEqualTo("s001");
        assertThat(Links.videoIdFromDeepLink("https://glimpse.example.com/v/s001/")).isEqualTo("s001");
        assertThat(Links.videoIdFromDeepLink("https://glimpse.example.com/x/s001")).isNull();
        assertThat(Links.videoIdFromDeepLink("http://glimpse.example.com/v/s001")).isNull(); // App Links are https
        assertThat(Links.videoIdFromDeepLink("https://glimpse.example.com/v/")).isNull();
    }

    @Test
    public void readsTheClipIdFromAnInstallReferrer() {
        assertThat(Links.videoIdFromReferrer("utm_source=share&utm_medium=link&video_id=s001")).isEqualTo("s001");
        assertThat(Links.videoIdFromReferrer("video_id%3Ds002")).isNull(); // still encoded: not a pair
        assertThat(Links.videoIdFromReferrer("video_id=s%5F3")).isEqualTo("s_3");
        assertThat(Links.videoIdFromReferrer("utm_source=google-play&utm_medium=organic")).isNull();
        assertThat(Links.videoIdFromReferrer("video_id=..%2Fetc")).isNull();
        assertThat(Links.videoIdFromReferrer("video_id=%E0%A4%A")).isNull(); // broken encoding
        assertThat(Links.videoIdFromReferrer("")).isNull();
        assertThat(Links.videoIdFromReferrer(null)).isNull();
    }

    @Test
    public void deepLinkAndIdRoundTrip() {
        assertThat(Links.videoIdFromDeepLink(Links.deepLink("v042"))).isEqualTo("v042");
    }

    @Test
    public void onlyAbsoluteHttpUrlsAreWebLinks() {
        assertThat(Links.isWebUrl("https://github.com/arnavjot")).isTrue();
        assertThat(Links.isWebUrl(" http://example.com/x?y=1 ")).isTrue();
        assertThat(Links.isWebUrl("intent://scan/#Intent;scheme=zxing;end")).isFalse();
        assertThat(Links.isWebUrl("javascript:alert(1)")).isFalse();
        assertThat(Links.isWebUrl("file:///sdcard/x")).isFalse();
        assertThat(Links.isWebUrl("glimpse://video/v1")).isFalse();
        assertThat(Links.isWebUrl("https://")).isFalse();
        assertThat(Links.isWebUrl(null)).isFalse();
    }

    @Test
    public void shareUrlJoinsTheBaseAndPathWithOneSlash() {
        assertThat(Links.shareUrl("http://192.168.1.40:8000/", "v001")).isEqualTo("http://192.168.1.40:8000/v/v001");
        assertThat(Links.shareUrl("https://glimpse.example", "v001")).isEqualTo("https://glimpse.example/v/v001");
    }
}
