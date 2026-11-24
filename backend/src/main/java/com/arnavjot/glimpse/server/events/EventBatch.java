package com.arnavjot.glimpse.server.events;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A batch of playback events from one device. */
public record EventBatch(
        @NotBlank @Size(min = 8, max = 64) String deviceId,
        @NotEmpty List<@Valid Event> events) {

    public record Event(
            // Generated on the device, so a retried batch is recognised and stored once.
            @NotBlank @Size(min = 8, max = 36) String eventId,
            @NotBlank @Size(max = 32) String videoId,
            @NotNull @Pattern(regexp = "impression|play_start|first_frame|rebuffer|watch|complete|error|ad_impression|ad_click")
            String type,
            @Min(0) @Max(86_400_000) Long valueMs,
            Boolean preload,
            @NotBlank @Size(max = 64) String sessionId,
            // OffsetDateTime: a timestamp without an offset is rejected as ambiguous.
            @NotNull OffsetDateTime occurredAt) {}

    public record Result(int accepted, int duplicates) {}
}
