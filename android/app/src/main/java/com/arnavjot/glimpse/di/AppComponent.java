package com.arnavjot.glimpse.di;

import com.arnavjot.glimpse.analytics.EventSender;
import com.arnavjot.glimpse.auth.SessionManager;
import com.arnavjot.glimpse.config.RemoteConfig;
import com.arnavjot.glimpse.repository.LikeSync;
import com.arnavjot.glimpse.ui.LoginActivity;
import com.arnavjot.glimpse.ui.MyUploadsActivity;
import com.arnavjot.glimpse.ui.UploadActivity;
import com.arnavjot.glimpse.upload.UploadClient;
import com.arnavjot.glimpse.ui.MainActivity;
import com.arnavjot.glimpse.ui.PerformanceActivity;

import java.util.concurrent.ExecutorService;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Component;

@Singleton
@Component(modules = {AppModule.class, ViewModelModule.class})
public interface AppComponent {

    void inject(MainActivity activity);

    void inject(PerformanceActivity activity);

    void inject(LoginActivity activity);

    SessionManager sessionManager();

    UploadClient uploadClient();

    void inject(UploadActivity activity);

    void inject(MyUploadsActivity activity);

    EventSender eventSender();

    LikeSync likeSync();

    RemoteConfig remoteConfig();

    @Named("io")
    ExecutorService io();
}
