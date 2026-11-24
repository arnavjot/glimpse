package com.arnavjot.glimpse.push;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arnavjot.glimpse.common.Links;

import java.util.Map;

/**
 * The data of a "new clip" push (sent by the backend's PushController). Parsed and
 * checked here, in plain Java, because it arrives from outside the app and decides
 * which deep link the notification opens.
 */
public final class NewClipMessage {

    static final String TYPE = "new_clip";

    @NonNull public final String videoId;
    @NonNull public final String title;
    @NonNull public final String creator;
    @Nullable public final String posterUrl;

    private NewClipMessage(@NonNull String videoId, @NonNull String title, @NonNull String creator,
                           @Nullable String posterUrl) {
        this.videoId = videoId;
        this.title = title;
        this.creator = creator;
        this.posterUrl = posterUrl;
    }

    /** The message, or null if it isn't a well-formed new-clip push. */
    @Nullable
    public static NewClipMessage from(@Nullable Map<String, String> data) {
        if (data == null || !TYPE.equals(data.get("type"))) return null;
        String id = data.get("video_id");
        String title = trimmed(data.get("title"), 100);
        String creator = trimmed(data.get("creator"), 60);
        if (!Links.isValidVideoId(id) || title == null || creator == null) return null;
        String poster = data.get("poster_url");
        return new NewClipMessage(id, title, creator, Links.isWebUrl(poster) ? poster : null);
    }

    @Nullable
    private static String trimmed(@Nullable String value, int max) {
        if (value == null) return null;
        String t = value.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }
}
