package com.arnavjot.glimpse;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.data.db.AppDatabase;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public final class TestSupport {

    private TestSupport() {}

    public static AppDatabase memoryDb() {
        Context context = ApplicationProvider.getApplicationContext();
        return Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    public static ApiInterface api(MockWebServer server) {
        // No silent retry: a dropped connection must fail at once, the way it would offline
        // (a retry would also wait forever on MockWebServer's empty response queue).
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false).build();
        return new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiInterface.class);
    }

    public static String video(String id, int likes) {
        return "{\"id\":\"" + id + "\",\"title\":\"Clip " + id + "\",\"creator\":\"tester\","
                + "\"duration_ms\":12000,\"width\":720,\"height\":1280,"
                + "\"hls_url\":\"http://media/" + id + "/index.m3u8\","
                + "\"poster_url\":\"http://media/" + id + "/poster.jpg\",\"like_count\":" + likes + "}";
    }

    public static String page(String nextCursor, String... ids) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) items.append(',');
            items.append(video(ids[i], 0));
        }
        String cursor = nextCursor == null ? "null" : "\"" + nextCursor + "\"";
        return "{\"items\":[" + items + "],\"next_cursor\":" + cursor + "}";
    }
}
