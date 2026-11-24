package com.arnavjot.glimpse.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.arnavjot.glimpse.pojo.UploadDto;

import java.util.Objects;

/** One row on My uploads: an upload still on this phone, or one the server knows. */
final class UploadRow {

    /** What the status line says. */
    enum Kind { SENDING, WAITING_NETWORK, PENDING, UPLOADED, PROCESSING, READY, FAILED }

    @NonNull final String key;
    @NonNull final String title;
    @NonNull final Kind kind;
    /** 0..100 while sending, else -1. */
    final int percent;
    @Nullable final String error;
    /** Set when ready: the clip in the feed. */
    @Nullable final String videoId;

    UploadRow(@NonNull String key, @NonNull String title, @NonNull Kind kind, int percent,
              @Nullable String error, @Nullable String videoId) {
        this.key = key;
        this.title = title;
        this.kind = kind;
        this.percent = percent;
        this.error = error;
        this.videoId = videoId;
    }

    /** A server upload. */
    @NonNull
    static UploadRow of(@NonNull UploadDto.Upload u) {
        Kind kind;
        switch (u.status == null ? "" : u.status) {
            case UploadDto.PENDING: kind = Kind.PENDING; break;
            case UploadDto.UPLOADED: kind = Kind.UPLOADED; break;
            case UploadDto.PROCESSING: kind = Kind.PROCESSING; break;
            case UploadDto.READY: kind = Kind.READY; break;
            default: kind = Kind.FAILED;
        }
        return new UploadRow("server:" + u.id, u.title == null ? "" : u.title, kind, -1,
                kind == Kind.FAILED ? u.error : null, kind == Kind.READY ? u.videoId : null);
    }

    /** A job still on this phone (key "local:&lt;work id&gt;"), not yet known to the server. */
    boolean isLocal() {
        return key.startsWith(LOCAL);
    }

    /** The WorkManager id of a local row, or the server's upload id. */
    @NonNull
    String id() {
        return key.substring(key.indexOf(':') + 1);
    }

    static final String LOCAL = "local:";

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadRow)) return false;
        UploadRow r = (UploadRow) o;
        return percent == r.percent && key.equals(r.key) && title.equals(r.title) && kind == r.kind
                && Objects.equals(error, r.error) && Objects.equals(videoId, r.videoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, kind, percent);
    }
}
