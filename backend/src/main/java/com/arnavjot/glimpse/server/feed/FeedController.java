package com.arnavjot.glimpse.server.feed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.common.GlimpseProperties;
import com.arnavjot.glimpse.server.experiments.ExperimentService;
import com.arnavjot.glimpse.server.sponsored.SponsoredInserter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@Validated
public class FeedController {

    private final FeedRepository repository;
    private final FeedCache cache;
    private final GlimpseProperties props;
    private final SponsoredInserter sponsored;
    private final ExperimentService experiments;

    /** The A/B test: control is newest first, treatment is "For You". */
    public static final String RANKING_EXPERIMENT = "feed_ranking";

    public FeedController(FeedRepository repository, FeedCache cache, GlimpseProperties props,
                          SponsoredInserter sponsored, ExperimentService experiments) {
        this.repository = repository;
        this.cache = cache;
        this.props = props;
        this.sponsored = sponsored;
        this.experiments = experiments;
    }

    /**
     * @param first optional clip id to show first (the app opened a shared link). Only on
     *              the first page; the clip is moved, not duplicated, and an unknown id
     *              is ignored, so the client can tell by checking the first item.
     */
    @GetMapping("/v1/feed")
    public ResponseEntity<FeedPage> feed(
            @RequestParam(required = false) @Size(max = 200) String cursor,
            @RequestParam(defaultValue = "10") @Min(1) int limit,
            @RequestParam(required = false) @Pattern(regexp = VideoController.ID_PATTERN) String first,
            // Per-install id the app sends with every request; drives the sponsored-clip cap.
            @RequestHeader(value = "X-Device-Id", required = false) @Size(min = 8, max = 64) String deviceId) {
        int size = Math.min(limit, props.feedPageMax());
        // The first page follows the device's variant. Later pages follow the cursor's own
        // type, so switching the experiment off never breaks a scroll in progress.
        boolean ranked = cursor != null
                ? CursorCodec.isRanked(cursor)
                : ExperimentService.TREATMENT.equals(experiments.variant(RANKING_EXPERIMENT, deviceId));
        FeedPage page = null;
        if (ranked) {
            try {
                // Not cached: a snapshot page is one primary-key range scan.
                page = repository.rankedPage(cursor, size).orElse(null); // null: no snapshot yet
            } catch (CursorCodec.BadCursorException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
            }
        }
        boolean servedRanked = page != null;
        String chronologicalCursor = ranked ? null : cursor;
        if (page == null) page = cache.get(chronologicalCursor, size).orElseGet(() -> {
            FeedPage fresh;
            try {
                fresh = repository.page(chronologicalCursor, size);
            } catch (CursorCodec.BadCursorException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
            }
            cache.put(chronologicalCursor, size, fresh);
            return fresh;
        });
        if (first != null && cursor == null) {
            // The shared page is the normal (cached) first page with one clip moved to the
            // top, so the cache stays one entry per page instead of one per shared clip.
            page = withFirst(page, first);
        }
        page = sponsored.insert(page, deviceId);
        // Clients may reuse a page briefly; like counts are eventually consistent anyway.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePrivate())
                .header("X-Feed-Order", servedRanked ? "for-you" : "newest")
                .body(page);
    }

    private FeedPage withFirst(FeedPage page, String firstId) {
        return repository.find(firstId).map(video -> {
            List<FeedPage.Video> items = new ArrayList<>(page.items().size() + 1);
            items.add(video);
            for (FeedPage.Video v : page.items()) {
                if (!v.id().equals(firstId)) items.add(v);
            }
            return new FeedPage(items, page.nextCursor());
        }).orElse(page);
    }
}
