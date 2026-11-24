package com.arnavjot.glimpse;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.arnavjot.glimpse.analytics.UploadEventsWorker;
import com.arnavjot.glimpse.config.RemoteConfig;
import com.arnavjot.glimpse.di.AppComponent;
import com.arnavjot.glimpse.di.AppModule;
import com.arnavjot.glimpse.di.DaggerAppComponent;
import com.arnavjot.glimpse.push.PushNotifications;

public class GlimpseApp extends Application {

    private AppComponent component;

    @Override
    public void onCreate() {
        super.onCreate();
        component = DaggerAppComponent.builder().appModule(new AppModule(this)).build();
        PushNotifications.createChannel(this);
        // Upload queued playback events whenever the app goes to the background.
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                // Launch and every return to the foreground: pick up flag changes (a kill
                // switch) without waiting for a restart. Skipped if fetched recently.
                RemoteConfig config = component.remoteConfig();
                component.io().execute(config::refreshIfStale);
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                UploadEventsWorker.enqueue(GlimpseApp.this);
            }
        });
    }

    public AppComponent component() {
        return component;
    }
}
