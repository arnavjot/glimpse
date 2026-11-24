package com.arnavjot.glimpse.pojo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.util.Objects;

/** One feed item, cached so the feed opens instantly and works offline. */
@Entity(tableName = "videos", indices = {@Index("position")})
public class VideoEntity {
    @PrimaryKey @NonNull public String id = "";
    /** Order in the feed (0 = top). */
    public int position;
    public String title;
    public String creator;
    public long durationMs;
    public String hlsUrl;
    public String posterUrl;
    public int likeCount;
    /** Whether this device has liked it (kept across feed refreshes). */
    public boolean liked;
    /**
     * Sponsored clip from the server. Never shown offline: the repository deletes these
     * whenever the feed can't be refreshed, so a cached ad is never replayed.
     */
    @ColumnInfo(defaultValue = "0")
    public boolean sponsored;
    /** "Learn more" target of a sponsored clip (http/https); null otherwise. */
    @Nullable public String ctaUrl;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoEntity)) return false;
        VideoEntity v = (VideoEntity) o;
        return position == v.position && durationMs == v.durationMs && likeCount == v.likeCount
                && liked == v.liked && id.equals(v.id) && Objects.equals(title, v.title)
                && Objects.equals(creator, v.creator) && Objects.equals(hlsUrl, v.hlsUrl)
                && Objects.equals(posterUrl, v.posterUrl)
                && sponsored == v.sponsored && Objects.equals(ctaUrl, v.ctaUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, position, likeCount, liked);
    }
}
