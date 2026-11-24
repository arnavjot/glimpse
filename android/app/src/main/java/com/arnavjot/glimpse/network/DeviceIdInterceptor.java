package com.arnavjot.glimpse.network;

import androidx.annotation.NonNull;

import com.arnavjot.glimpse.data.Prefs;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * Sends the anonymous per-install id (X-Device-Id) with every API call. The server uses
 * it for the sponsored-clip frequency cap; likes and events carry it in their bodies too.
 */
public final class DeviceIdInterceptor implements Interceptor {

    public static final String HEADER = "X-Device-Id";

    private final Prefs prefs;

    public DeviceIdInterceptor(@NonNull Prefs prefs) {
        this.prefs = prefs;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        return chain.proceed(chain.request().newBuilder().header(HEADER, prefs.deviceId()).build());
    }
}
