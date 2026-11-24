package com.arnavjot.glimpse.server.uploads;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.arnavjot.glimpse.server.auth.AuthUser;
import com.arnavjot.glimpse.server.auth.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Creator uploads (signed-in users only).
 * <ol>
 *   <li>POST /v1/uploads: returns a pre-signed URL; PUT the file there (straight to storage)</li>
 *   <li>POST /v1/uploads/{id}/complete: the file is checked and queued for processing</li>
 *   <li>GET /v1/uploads (or /{id}): status uploaded, processing, ready (with the video id) or failed</li>
 *   <li>DELETE /v1/uploads/{id}: gone from the feed at once; files are cleaned up shortly after</li>
 * </ol>
 */
@RestController
@Validated
public class UploadController {

    public record NewUpload(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 50) String contentType,
            @Positive long sizeBytes) {}

    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/v1/uploads")
    public ResponseEntity<UploadService.Ticket> create(@CurrentUser AuthUser user, @RequestBody @Valid NewUpload body,
            @RequestHeader(value = "X-Device-Id", required = false) @Size(max = 64) String deviceId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(uploads.create(user, body.title(), body.contentType(), body.sizeBytes(), deviceId));
    }

    @PostMapping("/v1/uploads/{id}/complete")
    public UploadService.Upload complete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return uploads.complete(user, id);
    }

    @GetMapping("/v1/uploads")
    public List<UploadService.Upload> mine(@CurrentUser AuthUser user) {
        return uploads.mine(user);
    }

    @DeleteMapping("/v1/uploads/{id}")
    public ResponseEntity<Void> delete(@CurrentUser AuthUser user, @PathVariable UUID id) {
        uploads.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/v1/uploads/{id}")
    public UploadService.Upload one(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return uploads.get(user, id);
    }
}
