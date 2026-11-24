package com.arnavjot.glimpse.server.feed;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.constraints.Pattern;

/** A single video, for opening a shared link to a clip that isn't in the app's feed yet. */
@RestController
@Validated
public class VideoController {

    /** Video ids are short slugs; anything else is rejected before it reaches SQL. */
    public static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final FeedRepository repository;

    public VideoController(FeedRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/v1/videos/{videoId}")
    public ResponseEntity<FeedPage.Video> video(@PathVariable @Pattern(regexp = ID_PATTERN) String videoId) {
        FeedPage.Video video = repository.find(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such video"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePrivate())
                .body(video);
    }
}
