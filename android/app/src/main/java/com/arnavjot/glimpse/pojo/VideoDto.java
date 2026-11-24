package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

public final class VideoDto {
    @SerializedName("id") public String id;
    @SerializedName("title") public String title;
    @SerializedName("creator") public String creator;
    @SerializedName("duration_ms") public long durationMs;
    @SerializedName("width") public int width;
    @SerializedName("height") public int height;
    @SerializedName("hls_url") public String hlsUrl;
    @SerializedName("poster_url") public String posterUrl;
    @SerializedName("like_count") public int likeCount;
    /** A sponsored clip the server inserted; such items have a ctaUrl and no likes. */
    @SerializedName("sponsored") public boolean sponsored;
    @SerializedName("cta_url") public String ctaUrl;
}
