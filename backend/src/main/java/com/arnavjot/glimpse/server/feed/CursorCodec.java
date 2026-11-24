package com.arnavjot.glimpse.server.feed;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * The feed cursor: the (published_at, id) of the last item on a page, base64url
 * encoded so clients treat it as an opaque token rather than parsing it.
 */
public final class CursorCodec {

    public record Position(Instant publishedAt, String id) {}

    /** A place in a "For You" ranking snapshot: the snapshot and the last rank shown. */
    public record RankedPosition(long generation, int rank) {}

    private static final String RANKED = "r|";

    public static final class BadCursorException extends RuntimeException {
        BadCursorException() {
            super("invalid cursor");
        }
    }

    private CursorCodec() {}

    public static String encode(Instant publishedAt, String id) {
        String raw = publishedAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String encodeRanked(long generation, int rank) {
        String raw = RANKED + generation + "|" + rank;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** The cursor's own type decides how the next page is built, whatever variant the device is in now. */
    public static boolean isRanked(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).startsWith(RANKED);
        } catch (IllegalArgumentException e) {
            return false; // decode() reports it as a bad cursor
        }
    }

    public static RankedPosition decodeRanked(String cursor) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3 || !"r".equals(parts[0])) throw new BadCursorException();
            int rank = Integer.parseInt(parts[2]);
            if (rank < 0) throw new BadCursorException();
            return new RankedPosition(Long.parseLong(parts[1]), rank);
        } catch (IllegalArgumentException e) {
            throw new BadCursorException();
        }
    }

    public static Position decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar <= 0 || bar == raw.length() - 1) throw new BadCursorException();
            return new Position(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BadCursorException();
        }
    }
}
