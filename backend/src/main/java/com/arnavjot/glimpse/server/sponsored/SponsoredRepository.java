package com.arnavjot.glimpse.server.sponsored;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.arnavjot.glimpse.server.common.GlimpseProperties;
import com.arnavjot.glimpse.server.feed.FeedPage;

/**
 * Chooses the sponsored clip for a device. Impressions come from the app's analytics
 * (event type ad_impression), so the cap counts clips actually shown, not served.
 */
@Repository
public class SponsoredRepository {

    private final JdbcTemplate jdbc;
    private final GlimpseProperties clips;

    public SponsoredRepository(JdbcTemplate jdbc, GlimpseProperties clips) {
        this.jdbc = jdbc;
        this.clips = clips;
    }

    /** Sponsored impressions this device has had in the last 24 hours. */
    public int impressionsToday(String deviceId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM playback_events WHERE device_id = ? "
                + "AND type = 'ad_impression' AND occurred_at > now() - interval '1 day'", Integer.class, deviceId);
        return n == null ? 0 : n;
    }

    /** The active creative this device has seen least today (ties broken at random). */
    public Optional<FeedPage.Video> leastSeenBy(String deviceId) {
        List<FeedPage.Video> found = new ArrayList<>(1);
        jdbc.query("""
                SELECT s.id, s.title, s.advertiser, s.duration_ms, s.width, s.height, s.hls_path,
                       s.poster_path, s.cta_url,
                       (SELECT count(*) FROM playback_events e
                         WHERE e.device_id = ? AND e.type = 'ad_impression' AND e.video_id = s.id
                           AND e.occurred_at > now() - interval '1 day') AS seen
                  FROM sponsored_clips s
                 WHERE s.active
                 ORDER BY seen, random()
                 LIMIT 1""", rs -> {
            found.add(new FeedPage.Video(rs.getString("id"), rs.getString("title"), rs.getString("advertiser"),
                    rs.getLong("duration_ms"), rs.getInt("width"), rs.getInt("height"),
                    clips.mediaUrl(rs.getString("hls_path")), clips.mediaUrl(rs.getString("poster_path")),
                    0, true, rs.getString("cta_url")));
        }, deviceId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }
}
