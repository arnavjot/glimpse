package com.arnavjot.glimpse.server.events;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.arnavjot.glimpse.server.common.GlimpseProperties;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;

/**
 * Stores each playback event exactly once.
 *
 * <p>The app retries a batch whenever it doesn't see a 2xx, including when the
 * server saved it but the response was lost. event_id is the primary key and
 * every insert is {@code ON CONFLICT DO NOTHING}, so a retried event is a no-op:
 * at-least-once delivery from the phone, exactly-once storage here.
 */
@RestController
public class EventsController {

    private static final String INSERT = "INSERT INTO playback_events "
            + "(event_id, video_id, type, value_ms, preload, device_id, session_id, occurred_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final GlimpseProperties props;
    private final MeterRegistry meters;

    public EventsController(JdbcTemplate jdbc, GlimpseProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.props = props;
        this.meters = meters;
    }

    @PostMapping("/v1/events")
    @Transactional
    public EventBatch.Result ingest(@Valid @RequestBody EventBatch batch) {
        if (batch.events().size() > props.eventsBatchMax()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "at most " + props.eventsBatchMax() + " events per batch");
        }
        // An id repeated inside one batch counts once.
        Map<String, EventBatch.Event> unique = new LinkedHashMap<>();
        for (EventBatch.Event e : batch.events()) unique.putIfAbsent(e.eventId(), e);

        List<Object[]> args = new ArrayList<>(unique.size());
        for (EventBatch.Event e : unique.values()) {
            args.add(new Object[] {e.eventId(), e.videoId(), e.type(), e.valueMs(), e.preload(),
                    batch.deviceId(), e.sessionId(), e.occurredAt().withOffsetSameInstant(ZoneOffset.UTC)});
        }
        int[] types = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BOOLEAN,
                Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE};
        int accepted = 0;
        for (int rows : jdbc.batchUpdate(INSERT, args, types)) accepted += rows; // 1 = stored, 0 = duplicate
        int duplicates = batch.events().size() - accepted;
        meters.counter("glimpse.events", "result", "accepted").increment(accepted);
        meters.counter("glimpse.events", "result", "duplicate").increment(duplicates);
        return new EventBatch.Result(accepted, duplicates);
    }
}
