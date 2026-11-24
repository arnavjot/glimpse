package com.arnavjot.glimpse.server.uploads;

import java.nio.file.Path;

/** Turns an uploaded original into the feed's format. An interface so tests can swap ffmpeg out. */
public interface Transcoder {

    /**
     * Writes adaptive HLS to {@code outDir}: index.m3u8 (the master playlist), one playlist
     * and segment set per quality, and poster.jpg.
     *
     * @return the clip's duration in milliseconds (after cutting to {@code maxSeconds})
     * @throws TranscodeException if the file isn't a playable video or ffmpeg fails
     */
    long transcode(Path original, Path outDir, int maxSeconds) throws TranscodeException;

    final class TranscodeException extends Exception {
        /** Safe to show the creator (no paths or ffmpeg output). */
        public final String userMessage;

        public TranscodeException(String userMessage, String detail) {
            super(userMessage + ": " + detail);
            this.userMessage = userMessage;
        }
    }
}
