package com.arnavjot.glimpse.pojo;

import androidx.annotation.Nullable;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public final class FeedPageDto {
    @SerializedName("items") public List<VideoDto> items;
    /** Null when there are no more pages. */
    @Nullable @SerializedName("next_cursor") public String nextCursor;
}
