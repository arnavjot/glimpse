package com.arnavjot.glimpse.network;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import okhttp3.HttpUrl;

public class BaseUrlInterceptorTest {

    @Test
    public void movesTheRequestOntoTheConfiguredServer() {
        HttpUrl request = HttpUrl.get("http://10.0.2.2:8000/v1/feed?limit=10");
        HttpUrl base = HttpUrl.get("http://192.168.1.20:8000/");
        assertThat(BaseUrlInterceptor.rewrite(request, base).toString())
                .isEqualTo("http://192.168.1.20:8000/v1/feed?limit=10");
    }

    @Test
    public void keepsAPathPrefixOnTheServerAddress() {
        HttpUrl request = HttpUrl.get("http://10.0.2.2:8000/v1/events");
        HttpUrl base = HttpUrl.get("https://example.com/glimpse/");
        assertThat(BaseUrlInterceptor.rewrite(request, base).toString())
                .isEqualTo("https://example.com/glimpse/v1/events");
    }
}
