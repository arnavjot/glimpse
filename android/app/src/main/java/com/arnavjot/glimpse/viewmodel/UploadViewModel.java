package com.arnavjot.glimpse.viewmodel;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.arnavjot.glimpse.upload.UploadVideoWorker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Posting a picked clip: check it, copy it into the app's own storage, and hand it to
 * the background upload job. The copy matters: the picker's permission to read the
 * original ends with this screen, while the upload may run (or resume) much later.
 */
public class UploadViewModel extends ViewModel {

    public static final class UiState {
        public final boolean busy;
        public final boolean done;
        @Nullable public final String error;

        UiState(boolean busy, boolean done, @Nullable String error) {
            this.busy = busy;
            this.done = done;
            this.error = error;
        }
    }

    /** Matches the server's limit. */
    static final long MAX_BYTES = 200L * 1024 * 1024;
    static final int MAX_TITLE = 100;
    static final Set<String> TYPES = Set.of("video/mp4", "video/quicktime", "video/webm", "video/3gpp");

    private final Context app;
    private final ExecutorService io;
    private final MutableLiveData<UiState> state = new MutableLiveData<>(new UiState(false, false, null));

    @Inject
    public UploadViewModel(Context app, @Named("io") ExecutorService io) {
        this.app = app;
        this.io = io;
    }

    public LiveData<UiState> state() {
        return state;
    }

    public void post(@NonNull Uri video, @NonNull String title) {
        UiState now = state.getValue();
        if (now != null && now.busy) return;
        String problem = checkTitle(title);
        if (problem != null) {
            state.setValue(new UiState(false, false, problem));
            return;
        }
        state.setValue(new UiState(true, false, null));
        io.execute(() -> {
            File copy = null;
            try {
                ContentResolver resolver = app.getContentResolver();
                String mime = resolver.getType(video);
                if (mime == null || !TYPES.contains(mime)) {
                    state.postValue(new UiState(false, false, "Use an MP4, MOV, WebM or 3GP video."));
                    return;
                }
                long size = sizeOf(resolver, video);
                if (size > MAX_BYTES) {
                    state.postValue(new UiState(false, false, tooBig()));
                    return;
                }
                copy = copyToCache(resolver, video, mime);
                if (copy.length() > MAX_BYTES) { // size unknown up front, or it lied
                    //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                    state.postValue(new UiState(false, false, tooBig()));
                    return;
                }
                UploadVideoWorker.enqueue(app, copy, title.trim(), mime);
                state.postValue(new UiState(false, true, null));
            } catch (IOException | SecurityException e) {
                if (copy != null) //noinspection ResultOfMethodCallIgnored
                    copy.delete();
                state.postValue(new UiState(false, false, "Couldn't read that video. Try picking it again."));
            }
        });
    }

    /**
     * Copies left by uploads cancelled before they ever started (their job never ran, so
     * it couldn't delete them). No live upload waits this long: retries finish in hours.
     */
    static void pruneOldCopies(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        for (File f : files) {
            if (f.lastModified() < cutoff) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    @Nullable
    static String checkTitle(@NonNull String title) {
        String t = title.trim();
        if (t.isEmpty()) return "Give your clip a title.";
        if (t.length() > MAX_TITLE) return "Keep the title under " + MAX_TITLE + " characters.";
        return null;
    }

    private static String tooBig() {
        return "Videos can be up to " + MAX_BYTES / (1024 * 1024) + " MB.";
    }

    /** -1 if the provider doesn't say. */
    private static long sizeOf(@NonNull ContentResolver resolver, @NonNull Uri uri) {
        try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        }
        return -1;
    }

    @NonNull
    private File copyToCache(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull String mime)
            throws IOException {
        File dir = new File(app.getCacheDir(), "uploads");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        pruneOldCopies(dir);
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        File out = new File(dir, UUID.randomUUID() + "." + (ext != null ? ext : "mp4"));
        try (InputStream in = resolver.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("no stream for " + uri);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) break; // stop copying a file that's too big
                os.write(buf, 0, n);
            }
        }
        return out;
    }
}
