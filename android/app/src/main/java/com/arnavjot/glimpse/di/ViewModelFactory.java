package com.arnavjot.glimpse.di;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Creates any ViewModel Dagger knows about, so ViewModels get constructor injection. */
@Singleton
public class ViewModelFactory implements ViewModelProvider.Factory {

    private final Map<Class<? extends ViewModel>, Provider<ViewModel>> providers;

    @Inject
    public ViewModelFactory(Map<Class<? extends ViewModel>, Provider<ViewModel>> providers) {
        this.providers = providers;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        Provider<ViewModel> provider = providers.get(modelClass);
        if (provider == null) throw new IllegalArgumentException("Unknown ViewModel " + modelClass);
        return (T) provider.get();
    }
}
