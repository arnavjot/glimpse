package com.arnavjot.glimpse.di;

import android.content.Context;

import com.arnavjot.glimpse.BuildConfig;
import com.arnavjot.glimpse.auth.AuthInterceptor;
import com.arnavjot.glimpse.auth.KeystoreSessionStore;
import com.arnavjot.glimpse.auth.SessionManager;
import com.arnavjot.glimpse.data.Prefs;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.network.AuthApi;
import com.arnavjot.glimpse.network.BaseUrlInterceptor;
import com.arnavjot.glimpse.network.DeviceIdInterceptor;
import com.arnavjot.glimpse.upload.UploadClient;

import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Networking. Two clients share one base (server address, device id, timeouts):
 * <ul>
 *   <li>"plain": sign-in calls only. No auth interceptor (a refresh can't trigger a
 *       refresh) and no body logging (passwords and tokens stay out of Logcat).</li>
 *   <li>the main client: everything else, with the access token attached and refreshed.</li>
 * </ul>
 */
@Module
public class NetworkModule {

    @Provides
    @Singleton
    @Named("base")
    OkHttpClient baseClient(Prefs prefs) {
        return new OkHttpClient.Builder()
                .addInterceptor(new BaseUrlInterceptor(prefs))
                .addInterceptor(new DeviceIdInterceptor(prefs))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Provides
    @Singleton
    AuthApi authApi(@Named("base") OkHttpClient base) {
        OkHttpClient plain = base.newBuilder()
                .addInterceptor(logging(HttpLoggingInterceptor.Level.HEADERS))
                .build();
        return buildRetrofit(plain).create(AuthApi.class);
    }

    @Provides
    @Singleton
    SessionManager sessionManager(AuthApi authApi, Context app) {
        return new SessionManager(authApi, new KeystoreSessionStore(app), System::currentTimeMillis);
    }

    @Provides
    @Singleton
    OkHttpClient okHttp(@Named("base") OkHttpClient base, SessionManager sessions) {
        AuthInterceptor auth = new AuthInterceptor(sessions);
        return base.newBuilder()
                .addInterceptor(auth)     // attaches (and early-refreshes) the access token
                .authenticator(auth)      // on a 401: refresh once, retry once
                .addInterceptor(logging(HttpLoggingInterceptor.Level.BODY))
                .build();
    }

    @Provides
    @Singleton
    Retrofit retrofit(OkHttpClient client) {
        return buildRetrofit(client);
    }

    @Provides
    @Singleton
    ApiInterface api(Retrofit retrofit) {
        return retrofit.create(ApiInterface.class);
    }

    /**
     * Uploads to storage use pre-signed URLs: no API interceptors (the URL carries its own
     * authorisation, and its host is part of the signature, so it must not be rewritten).
     * Long write timeout for big files on slow networks.
     */
    @Provides
    @Singleton
    UploadClient uploadClient(ApiInterface api) {
        OkHttpClient storage = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.MINUTES)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();
        return new UploadClient(api, storage, System::currentTimeMillis);
    }

    private static Retrofit buildRetrofit(OkHttpClient client) {
        return new Retrofit.Builder()
                .baseUrl(BuildConfig.DEFAULT_API_URL) // replaced per request by BaseUrlInterceptor
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
    }

    /**
     * Logcat request logs, debug builds only (release logs nothing). The Authorization
     * header is always redacted.
     */
    private static HttpLoggingInterceptor logging(HttpLoggingInterceptor.Level debugLevel) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(BuildConfig.DEBUG ? debugLevel : HttpLoggingInterceptor.Level.NONE);
        logging.redactHeader("Authorization");
        return logging;
    }
}
