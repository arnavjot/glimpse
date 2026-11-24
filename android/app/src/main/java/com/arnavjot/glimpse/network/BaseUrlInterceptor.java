package com.arnavjot.glimpse.network;

import androidx.annotation.NonNull;

import com.arnavjot.glimpse.data.Prefs;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Points every API call at the server chosen in Settings, so the address can be
 * changed at runtime (emulator vs phone on the same Wi-Fi) without rebuilding
 * Retrofit. Retrofit is built with a placeholder base URL; this swaps in the
 * real scheme, host and port and keeps the path.
 */
public final class BaseUrlInterceptor implements Interceptor {

    private final Prefs prefs;

    public BaseUrlInterceptor(Prefs prefs) {
        this.prefs = prefs;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl target = HttpUrl.parse(prefs.apiUrl());
        if (target == null) {
            return chain.proceed(request);
        }
        HttpUrl url = rewrite(request.url(), target);
        return chain.proceed(request.newBuilder().url(url).build());
    }

    /** Moves {@code url} onto {@code base}, keeping base's path prefix. */
    static HttpUrl rewrite(HttpUrl url, HttpUrl base) {
        String prefix = base.encodedPath().endsWith("/")
                ? base.encodedPath().substring(0, base.encodedPath().length() - 1)
                : base.encodedPath();
        return url.newBuilder()
                .scheme(base.scheme())
                .host(base.host())
                .port(base.port())
                .encodedPath(prefix + url.encodedPath())
                .build();
    }
}
