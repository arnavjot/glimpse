package com.arnavjot.glimpse.server.sponsored;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.arnavjot.glimpse.server.config.RemoteConfigService;
import com.arnavjot.glimpse.server.feed.FeedPage;

/**
 * Puts one sponsored clip into a feed page, per request. The page itself stays cached
 * and shared; only this step is per device. No device id (an old client, a crawler):
 * no sponsored clip. Over the daily cap, or no active creative: the page is unchanged.
 */
@Component
public class SponsoredInserter {

    private final SponsoredRepository repository;
    private final SponsoredProperties props;
    private final RemoteConfigService config;

    public SponsoredInserter(SponsoredRepository repository, SponsoredProperties props, RemoteConfigService config) {
        this.repository = repository;
        this.props = props;
        this.config = config;
    }

    public FeedPage insert(FeedPage page, String deviceId) {
        // Remote config: "sponsored_clips" is the kill switch (and rollout), "sponsored_slot" the position.
        if (deviceId == null || !config.isOn("sponsored_clips", deviceId)) return page;
        int slot = config.intValue("sponsored_slot", deviceId, props.slot());
        if (slot <= 0 || page.items().size() < slot) return page;
        if (repository.impressionsToday(deviceId) >= props.dailyCap()) return page;
        return repository.leastSeenBy(deviceId).map(ad -> {
            List<FeedPage.Video> items = new ArrayList<>(page.items().size() + 1);
            items.addAll(page.items().subList(0, slot));
            items.add(ad);
            items.addAll(page.items().subList(slot, page.items().size()));
            return new FeedPage(items, page.nextCursor());
        }).orElse(page);
    }
}
