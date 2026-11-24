package com.arnavjot.glimpse.server.push;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.common.AdminAuth;
import com.arnavjot.glimpse.server.config.RemoteConfigService;
import com.arnavjot.glimpse.server.feed.FeedPage;
import com.arnavjot.glimpse.server.feed.FeedRepository;
import com.arnavjot.glimpse.server.feed.VideoController;

import jakarta.validation.constraints.Pattern;

/**
 * Admin: announce a clip to everyone with notifications on.
 *
 * <pre>curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8000/v1/admin/push/new-clip/s001</pre>
 *
 * Off (404) unless ADMIN_TOKEN is set; 401 for a wrong token; 503 if Firebase isn't set up.
 */
@RestController
@Validated
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    private final PushSender sender;
    private final AdminAuth admin;
    private final RemoteConfigService config;
    private final FeedRepository videos;

    public PushController(PushSender sender, AdminAuth admin, RemoteConfigService config, FeedRepository videos) {
        this.sender = sender;
        this.admin = admin;
        this.config = config;
        this.videos = videos;
    }

    @PostMapping("/v1/admin/push/new-clip/{videoId}")
    public ResponseEntity<Map<String, String>> newClip(
            @PathVariable @Pattern(regexp = VideoController.ID_PATTERN) String videoId,
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        // Kill switch: the flag's enabled bit (a rollout percentage doesn't apply to a broadcast).
        boolean killed = config.get("push_notifications").map(e -> !e.enabled()).orElse(false);
        if (killed) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "push is switched off (remote config)");
        }
        if (!sender.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "push is not configured");
        }
        FeedPage.Video video = videos.find(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such video"));
        try {
            String messageId = sender.sendNewClip(video);
            log.info("Pushed new clip {} ({})", videoId, messageId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("message_id", messageId));
        } catch (PushSender.PushException e) {
            log.warn("Push for {} failed", videoId, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "push failed");
        }
    }
}
