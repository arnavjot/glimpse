package com.arnavjot.glimpse.server.feed;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.arnavjot.glimpse.server.common.GlimpseProperties;

/**
 * Keyset pagination: each page continues from the last (published_at, id) it
 * showed, instead of OFFSET. Page 100 costs the same as page 1 (one index
 * range scan), and a video published while someone scrolls never shifts items
 * between pages or shows one twice.
 */
@Repository
public class FeedRepository {

    private final JdbcTemplate jdbc;
    private final GlimpseProperties props;

    public FeedRepository(JdbcTemplate jdbc, GlimpseProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    private static final String SELECT = "SELECT id, title, creator, duration_ms, width, height, hls_path, "
            + "poster_path, published_at, like_count FROM videos ";

    /** One video by id, for share links. Empty if there is no such video. */
    public Optional<FeedPage.Video> find(String id) {
        List<FeedPage.Video> found = new ArrayList<>(1);
        jdbc.query(SELECT + "WHERE id = ?", rs -> {
            found.add(Row.of(rs).toVideo(props));
        }, id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public FeedPage page(String cursor, int limit) {
        String select = SELECT;
        String order = " ORDER BY published_at DESC, id DESC LIMIT ?";
        List<Row> found = new ArrayList<>();
        if (cursor == null) {
            jdbc.query(select + order, rs -> {
                found.add(Row.of(rs));
            }, limit + 1);
        } else {
            CursorCodec.Position after = CursorCodec.decode(cursor);
            jdbc.query(select + "WHERE (published_at, id) < (?, ?)" + order, rs -> {
                found.add(Row.of(rs));
            }, Timestamp.from(after.publishedAt()), after.id(), limit + 1);
        }
        boolean more = found.size() > limit;
        List<Row> page = more ? found.subList(0, limit) : found;
        List<FeedPage.Video> items = new ArrayList<>(page.size());
        for (Row r : page) items.add(r.toVideo(props));
        Row last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = more && last != null ? CursorCodec.encode(last.publishedAt, last.id) : null;
        return new FeedPage(items, next);
    }

    /**
     * A page of "For You": clips in the order of one frozen ranking snapshot. The cursor
     * names the snapshot, so new scores never reorder what someone is scrolling through.
     * Empty when no snapshot exists yet (fresh install): the caller falls back to newest first.
     *
     * <p>Clips deleted since the snapshot are skipped (the join). Clips published since then
     * appear in the next snapshot, minutes later. If a cursor's snapshot has expired, the
     * scroll continues at the same rank in the newest one.
     */
    public Optional<FeedPage> rankedPage(String cursor, int limit) {
        // Validate the cursor first: a bad one is a 400 even before any snapshot exists.
        CursorCodec.RankedPosition pos = cursor == null ? null : CursorCodec.decodeRanked(cursor);
        Long latest = jdbc.queryForObject("SELECT max(generation) FROM ranking_snapshots", Long.class);
        if (latest == null) return Optional.empty();
        long generation = latest;
        int after = 0;
        if (pos != null) {
            after = pos.rank();
            Boolean kept = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ranking_snapshots WHERE generation = ?)",
                    Boolean.class, pos.generation());
            if (Boolean.TRUE.equals(kept)) generation = pos.generation();
        }
        List<Row> found = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        jdbc.query("SELECT v.id, v.title, v.creator, v.duration_ms, v.width, v.height, v.hls_path, v.poster_path, "
                + "v.published_at, v.like_count, rc.rank FROM ranked_clips rc JOIN videos v ON v.id = rc.video_id "
                + "WHERE rc.generation = ? AND rc.rank > ? ORDER BY rc.rank LIMIT ?", rs -> {
            found.add(Row.of(rs));
            ranks.add(rs.getInt("rank"));
        }, generation, after, limit + 1);
        boolean more = found.size() > limit;
        int shown = more ? limit : found.size();
        List<FeedPage.Video> items = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) items.add(found.get(i).toVideo(props));
        String next = more ? CursorCodec.encodeRanked(generation, ranks.get(shown - 1)) : null;
        return Optional.of(new FeedPage(items, next));
    }

    private record Row(String id, String title, String creator, long durationMs, int width, int height,
                       String hlsPath, String posterPath, Instant publishedAt, int likeCount) {

        static Row of(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Row(rs.getString("id"), rs.getString("title"), rs.getString("creator"),
                    rs.getLong("duration_ms"), rs.getInt("width"), rs.getInt("height"),
                    rs.getString("hls_path"), rs.getString("poster_path"),
                    rs.getTimestamp("published_at").toInstant(), rs.getInt("like_count"));
        }

        FeedPage.Video toVideo(GlimpseProperties props) {
            return new FeedPage.Video(id, title, creator, durationMs, width, height,
                    props.mediaUrl(hlsPath), props.mediaUrl(posterPath), likeCount, false, null);
        }
    }
}
