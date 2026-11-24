package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

public final class EventBatchResultDto {
    @SerializedName("accepted") public int accepted;
    @SerializedName("duplicates") public int duplicates;
}
