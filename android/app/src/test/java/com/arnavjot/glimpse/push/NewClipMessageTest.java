package com.arnavjot.glimpse.push;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Push data comes from outside the app and picks the deep link: it is checked strictly. */
public class NewClipMessageTest {

    private static Map<String, String> valid() {
        Map<String, String> data = new HashMap<>();
        data.put("type", "new_clip");
        data.put("video_id", "s007");
        data.put("title", "Aurora borealis from orbit");
        data.put("creator", "NOAA");
        data.put("poster_url", "http://192.168.1.40:8080/media/s007/poster.jpg");
        return data;
    }

    @Test
    public void aWellFormedMessageIsRead() {
        NewClipMessage m = NewClipMessage.from(valid());
        assertThat(m).isNotNull();
        assertThat(m.videoId).isEqualTo("s007");
        assertThat(m.title).isEqualTo("Aurora borealis from orbit");
        assertThat(m.creator).isEqualTo("NOAA");
        assertThat(m.posterUrl).isEqualTo("http://192.168.1.40:8080/media/s007/poster.jpg");
    }

    @Test
    public void otherMessageTypesAreIgnored() {
        Map<String, String> data = valid();
        data.put("type", "promo");
        assertThat(NewClipMessage.from(data)).isNull();
        assertThat(NewClipMessage.from(null)).isNull();
    }

    @Test
    public void aBadVideoIdIsRejected() {
        Map<String, String> data = valid();
        data.put("video_id", "../../settings");
        assertThat(NewClipMessage.from(data)).isNull();
        data.remove("video_id");
        assertThat(NewClipMessage.from(data)).isNull();
    }

    @Test
    public void aBlankTitleOrCreatorIsRejected() {
        Map<String, String> data = valid();
        data.put("title", "   ");
        assertThat(NewClipMessage.from(data)).isNull();
    }

    @Test
    public void aNonWebPosterIsDroppedButTheMessageKept() {
        Map<String, String> data = valid();
        data.put("poster_url", "file:///data/data/other.app/secret.jpg");
        NewClipMessage m = NewClipMessage.from(data);
        assertThat(m).isNotNull();
        assertThat(m.posterUrl).isNull();
    }

    @Test
    public void longTextIsCut() {
        Map<String, String> data = valid();
        data.put("title", "x".repeat(500));
        assertThat(NewClipMessage.from(data).title).hasLength(100);
    }
}
