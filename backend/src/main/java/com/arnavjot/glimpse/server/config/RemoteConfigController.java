package com.arnavjot.glimpse.server.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.common.AdminAuth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * GET /v1/config: the flags and values for the calling device (X-Device-Id). An ETag
 * makes the app's frequent re-checks cheap: unchanged config is a 304 with no body.
 *
 * <p>Admin (X-Admin-Token): list, update (enabled / rollout_percent / value) and the
 * kill switch, POST /v1/admin/config/{key}/kill.
 */
@RestController
@Validated
public class RemoteConfigController {

    static final int DEFAULT_REFRESH_SECONDS = 300;

    public record ConfigUpdate(
            Boolean enabled,
            @Min(0) @Max(100) Integer rolloutPercent,
            @Size(max = 500) String value) {}

    private final RemoteConfigService config;
    private final AdminAuth admin;

    public RemoteConfigController(RemoteConfigService config, AdminAuth admin) {
        this.config = config;
        this.admin = admin;
    }

    @GetMapping("/v1/config")
    public ResponseEntity<Map<String, Object>> forDevice(
            @RequestHeader(value = "X-Device-Id", required = false) @Size(min = 8, max = 64) String deviceId,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        Map<String, Object> body = new LinkedHashMap<>(config.resolveFor(deviceId));
        body.put("refresh_seconds", config.intValue("config_refresh_seconds", deviceId, DEFAULT_REFRESH_SECONDS));
        String etag = etag(body);
        // no-cache: the app may keep it, but must revalidate (cheap, thanks to the ETag).
        CacheControl revalidate = CacheControl.noCache().cachePrivate();
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(revalidate).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(revalidate).body(body);
    }

    @GetMapping("/v1/admin/config")
    public List<RemoteConfigService.Entry> list(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        config.invalidate();
        return config.all();
    }

    @PutMapping("/v1/admin/config/{key}")
    public RemoteConfigService.Entry update(@PathVariable @Size(max = 64) String key,
                                           @RequestBody @Valid ConfigUpdate change,
                                           @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        return config.update(key, change.enabled(), change.rolloutPercent(), change.value())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such flag"));
    }

    /** The kill switch: off for every device, effective within seconds. */
    @PostMapping("/v1/admin/config/{key}/kill")
    public RemoteConfigService.Entry kill(@PathVariable @Size(max = 64) String key,
                                         @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        admin.require(token);
        return config.update(key, false, null, null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such flag"));
    }

    /** A strong ETag from the content (the map is ordered, so equal config hashes equal). */
    static String etag(Map<String, Object> body) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(h, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
