package com.arnavjot.glimpse.pojo;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/** GET /v1/config: this device's feature flags and remote values. */
public final class RemoteConfigDto {
    @SerializedName("flags") public Map<String, Boolean> flags;
    @SerializedName("values") public Map<String, String> values;
    @SerializedName("refresh_seconds") public int refreshSeconds;
}
