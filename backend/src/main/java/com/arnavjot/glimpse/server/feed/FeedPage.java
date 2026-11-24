package com.arnavjot.glimpse.server.feed;

import java.util.List;

/** One page of the feed. nextCursor is null on the last page. */
public record FeedPage(List<Video> items, String nextCursor) {

    public record Video(
            String id,
            String title,
            String creator,
            long durationMs,
            int width,
            int height,
            String hlsUrl,
            String posterUrl,
            int likeCount,
            // Sponsored clips: labelled in the app, no like/share, "Learn more" opens ctaUrl.
            boolean sponsored,
            String ctaUrl) {}
}
