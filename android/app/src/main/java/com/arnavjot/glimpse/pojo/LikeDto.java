package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

public final class LikeDto {
    @SerializedName("video_id") public String videoId;
    @SerializedName("liked") public boolean liked;
    @SerializedName("like_count") public int likeCount;

    /** Request body for PUT /v1/videos/{id}/like. */
    public static final class Body {
        @SerializedName("device_id") public final String deviceId;

        public Body(String deviceId) {
            this.deviceId = deviceId;
        }
    }
}
