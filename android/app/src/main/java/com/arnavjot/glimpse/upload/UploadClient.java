package com.arnavjot.glimpse.upload;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.arnavjot.glimpse.network.ApiInterface;
import com.arnavjot.glimpse.pojo.AuthDto;
import com.arnavjot.glimpse.pojo.UploadDto;
import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;
import retrofit2.Response;

/**
 * One upload, start to finish: get a pre-signed URL, PUT the file straight to storage
 * (streamed, with progress), then tell the server it's there.
 *
 * <p>Built for retries: the upload id and URL are kept in {@link JobState}, so a retry
 * reuses them (until the URL is close to expiring) instead of creating a new upload.
 * Every step is idempotent: re-sending the PUT overwrites the same object, completing
 * twice returns the current status.
 *
 * <p>{@link PermanentFailure}: retrying can't help (file rejected, uploads switched off,
 * signed out). An {@link IOException}: temporary (offline, server busy), retry later.
 */
public class UploadClient {

    /** Upload progress, in bytes. */
    public interface Progress {
        void onProgress(long sent, long total);
    }

    /** Survives retries of one upload job. */
    public interface JobState {
        @Nullable String uploadId();

        @Nullable String uploadUrl();

        long urlExpiresAtMillis();

        void save(@NonNull String uploadId, @NonNull String uploadUrl, long urlExpiresAtMillis);

        void clearUrl();
    }

    /** Won't succeed on retry; the message is fit to show the creator. */
    public static final class PermanentFailure extends Exception {
        public PermanentFailure(@NonNull String message) {
            super(message);
        }
    }

    /** The server's URL lasts 15 minutes; use it for at most 13, so a PUT never starts on a dying URL. */
    static final long URL_LIFETIME_MILLIS = TimeUnit.MINUTES.toMillis(13);

    private final ApiInterface api;
    private final OkHttpClient storage;
    private final LongSupplier clock;
    private final Gson gson = new Gson();

    /**
     * @param storage a client with no API interceptors: pre-signed URLs carry their own
     *                authorisation, and the host must not be rewritten (it's in the signature)
     */
    public UploadClient(@NonNull ApiInterface api, @NonNull OkHttpClient storage, @NonNull LongSupplier clock) {
        this.api = api;
        this.storage = storage;
        this.clock = clock;
    }

    @WorkerThread
    @NonNull
    public UploadDto.Upload upload(@NonNull File file, @NonNull String title, @NonNull String mimeType,
                                   @NonNull JobState state, @NonNull Progress progress)
            throws IOException, PermanentFailure {
        String id = state.uploadId();
        String url = state.uploadUrl();
        if (id == null || url == null || clock.getAsLong() >= state.urlExpiresAtMillis()) {
            Response<UploadDto.Ticket> r = api.createUpload(new UploadDto.NewUpload(title, mimeType, file.length()))
                    .execute();
            UploadDto.Ticket t = r.body();
            if (!r.isSuccessful() || t == null || t.upload == null) throw failure(r.code(), r.errorBody());
            id = t.upload.id;
            url = t.uploadUrl;
            state.save(id, url, clock.getAsLong() + URL_LIFETIME_MILLIS);
        }

        Request put = new Request.Builder().url(url).put(new FileBody(file, mimeType, progress)).build();
        try (okhttp3.Response r = storage.newCall(put).execute()) {
            if (r.code() == 403) {
                state.clearUrl(); // expired or refused signature: the retry asks for a new URL
                throw new IOException("upload URL refused (HTTP 403)");
            }
            if (!r.isSuccessful()) {
                if (r.code() >= 500 || r.code() == 408 || r.code() == 429) throw new IOException("storage HTTP " + r.code());
                throw new PermanentFailure("The upload was refused (HTTP " + r.code() + ").");
            }
        }

        Response<UploadDto.Upload> done = api.completeUpload(id).execute();
        UploadDto.Upload body = done.body();
        if (done.isSuccessful() && body != null) return body;
        if (done.code() == 409) throw new IOException("storage hasn't got the file yet"); // retry
        throw failure(done.code(), done.errorBody());
    }

    /**
     * 5xx, 408, 429: temporary, thrown here as IOException. Other 4xx: returned as a
     * PermanentFailure with the server's reason, for the caller to throw.
     */
    @NonNull
    private PermanentFailure failure(int code, @Nullable ResponseBody error) throws IOException {
        if (code >= 500 || code == 408 || code == 429 || code == 0) throw new IOException("server HTTP " + code);
        String detail = null;
        try {
            if (error != null) {
                AuthDto.Error e = gson.fromJson(error.charStream(), AuthDto.Error.class);
                if (e != null) detail = e.detail;
            }
        } catch (RuntimeException ignored) {
            // Not our JSON.
        }
        if (code == 401) return new PermanentFailure("Sign in again to post this clip.");
        return new PermanentFailure(detail != null && !detail.isEmpty()
                ? Character.toUpperCase(detail.charAt(0)) + detail.substring(1) + "."
                : "The upload was refused (HTTP " + code + ").");
    }

    /** Streams the file in 64 KB chunks, reporting progress; never loads it into memory. */
    static final class FileBody extends RequestBody {
        private static final int CHUNK = 64 * 1024;

        private final File file;
        private final MediaType type;
        private final Progress progress;

        FileBody(File file, String mimeType, Progress progress) {
            this.file = file;
            this.type = MediaType.get(mimeType);
            this.progress = progress;
        }

        @Override
        public MediaType contentType() {
            return type;
        }

        @Override
        public long contentLength() {
            return file.length();
        }

        @Override
        public void writeTo(@NonNull BufferedSink sink) throws IOException {
            long total = contentLength();
            long sent = 0;
            try (Source source = Okio.source(file)) {
                long n;
                while ((n = source.read(sink.getBuffer(), CHUNK)) != -1) {
                    sink.flush();
                    sent += n;
                    progress.onProgress(sent, total);
                }
            }
        }
    }
}
