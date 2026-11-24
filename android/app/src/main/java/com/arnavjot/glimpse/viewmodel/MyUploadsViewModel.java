package com.arnavjot.glimpse.viewmodel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.common.Event;
import com.arnavjot.glimpse.data.db.AppDatabase;
import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.UploadDto;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Named;

import retrofit2.Response;

/** A creator's uploads from the server. The screen refreshes while any are still in progress. */
public class MyUploadsViewModel extends ViewModel {

    /** One-shot result of a delete, for a Snackbar. */
    public enum DeleteResult { DELETED, FAILED }

    private final ApiInterface api;
    private final AppDatabase db;
    private final ExecutorService io;
    private final MutableLiveData<Event<DeleteResult>> deleteResults = new MutableLiveData<>();
    private final MutableLiveData<List<UploadDto.Upload>> uploads = new MutableLiveData<>();
    private final MutableLiveData<Boolean> offline = new MutableLiveData<>(false);
    private final AtomicBoolean loading = new AtomicBoolean();

    @Inject
    public MyUploadsViewModel(ApiInterface api, AppDatabase db, @Named("io") ExecutorService io) {
        this.api = api;
        this.db = db;
        this.io = io;
    }

    public LiveData<Event<DeleteResult>> deleteResults() {
        return deleteResults;
    }

    /**
     * Deletes an upload on the server. The clip also leaves this phone's cached feed at
     * once, instead of waiting for the next refresh.
     */
    public void delete(@NonNull String uploadId, @Nullable String videoId) {
        io.execute(() -> {
            try {
                Response<Void> r = api.deleteUpload(uploadId).execute();
                // 404: already gone (deleted elsewhere, or cleaned up): the outcome we wanted.
                if (r.isSuccessful() || r.code() == 404) {
                    if (videoId != null) db.videos().deleteById(videoId);
                    deleteResults.postValue(new Event<>(DeleteResult.DELETED));
                } else {
                    deleteResults.postValue(new Event<>(DeleteResult.FAILED));
                }
            } catch (IOException e) {
                deleteResults.postValue(new Event<>(DeleteResult.FAILED));
            }
            refresh();
        });
    }

    public LiveData<List<UploadDto.Upload>> uploads() {
        return uploads;
    }

    /** True when the last refresh couldn't reach the server (the list shown may be stale). */
    public LiveData<Boolean> offline() {
        return offline;
    }

    public void refresh() {
        if (!loading.compareAndSet(false, true)) return; // one request at a time
        io.execute(() -> {
            try {
                Response<List<UploadDto.Upload>> r = api.myUploads().execute();
                if (r.isSuccessful() && r.body() != null) {
                    uploads.postValue(r.body());
                    offline.postValue(false);
                } else {
                    offline.postValue(true);
                }
            } catch (IOException e) {
                offline.postValue(true);
            } finally {
                loading.set(false);
            }
        });
    }

    /**
     * Worth polling: something is waiting to be processed or being processed. (Sending is
     * tracked by the phone's own job, and a pending upload may be abandoned: neither counts.)
     */
    public static boolean anyInProgress(@Nullable List<UploadDto.Upload> list) {
        if (list == null) return false;
        for (UploadDto.Upload u : list) {
            if (inProgress(u.status)) return true;
        }
        return false;
    }

    static boolean inProgress(@Nullable String status) {
        return UploadDto.UPLOADED.equals(status) || UploadDto.PROCESSING.equals(status);
    }
}
