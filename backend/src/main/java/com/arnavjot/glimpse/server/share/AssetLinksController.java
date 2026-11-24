package com.arnavjot.glimpse.server.share;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Digital Asset Links: proves to Android that this domain belongs to the app, so its
 * https share links (/v/{id}) open the app straight away instead of the browser
 * (App Links, verified at install). Must be served over HTTPS from the share domain.
 */
@RestController
public class AssetLinksController {

    private final AppProperties app;

    public AssetLinksController(AppProperties app) {
        this.app = app;
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = "application/json")
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        if (app.sha256Fingerprints().isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> statement = Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", app.packageName(),
                        "sha256_cert_fingerprints", app.sha256Fingerprints()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(List.of(statement));
    }
}
