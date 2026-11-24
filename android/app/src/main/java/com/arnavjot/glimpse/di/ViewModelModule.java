package com.arnavjot.glimpse.di;

import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.viewmodel.FeedViewModel;
import com.arnavjot.glimpse.viewmodel.LoginViewModel;
import com.arnavjot.glimpse.viewmodel.MyUploadsViewModel;
import com.arnavjot.glimpse.viewmodel.UploadViewModel;
import com.arnavjot.glimpse.viewmodel.PerformanceViewModel;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;

@Module
public abstract class ViewModelModule {

    @Binds
    @IntoMap
    @ViewModelKey(FeedViewModel.class)
    abstract ViewModel feed(FeedViewModel vm);

    @Binds
    @IntoMap
    @ViewModelKey(PerformanceViewModel.class)
    abstract ViewModel performance(PerformanceViewModel vm);

    @Binds
    @IntoMap
    @ViewModelKey(LoginViewModel.class)
    abstract ViewModel login(LoginViewModel vm);

    @Binds
    @IntoMap
    @ViewModelKey(UploadViewModel.class)
    abstract ViewModel upload(UploadViewModel vm);

    @Binds
    @IntoMap
    @ViewModelKey(MyUploadsViewModel.class)
    abstract ViewModel myUploads(MyUploadsViewModel vm);
}
