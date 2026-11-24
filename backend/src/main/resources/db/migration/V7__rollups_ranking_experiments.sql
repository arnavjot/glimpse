-- Rollups: playback events folded into hourly totals per clip. Stats and ranking read
-- these small tables instead of scanning the raw event log.
CREATE TABLE clip_stats_hourly (
    video_id          VARCHAR(32) NOT NULL,
    hour              TIMESTAMPTZ NOT NULL,     -- start of the hour (UTC)
    plays             BIGINT      NOT NULL DEFAULT 0,
    completions       BIGINT      NOT NULL DEFAULT 0,
    watch_ms          BIGINT      NOT NULL DEFAULT 0,
    first_frames      BIGINT      NOT NULL DEFAULT 0,
    first_frame_ms    BIGINT      NOT NULL DEFAULT 0,  -- sum, for the average
    rebuffers         BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (video_id, hour)
);

-- How far each rollup has read the event log (by received_at). Advanced in the same
-- transaction as the totals it produced, so every event is counted exactly once.
CREATE TABLE rollup_state (
    name            VARCHAR(50) PRIMARY KEY,
    processed_upto  TIMESTAMPTZ NOT NULL
);
INSERT INTO rollup_state (name, processed_upto) VALUES ('clip_stats_hourly', '-infinity');

-- The rollup reads events past its bookmark.
CREATE INDEX ix_events_received ON playback_events (received_at);
-- Experiment reports read each exposed device's events.
CREATE INDEX ix_events_device_time ON playback_events (device_id, occurred_at);

-- "For You" ranking, frozen into numbered snapshots. A feed cursor names its snapshot, so
-- scores changing mid-scroll never reorder pages (no duplicates, no skipped clips).
CREATE TABLE ranking_snapshots (
    generation  BIGINT      PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ranked_clips (
    generation  BIGINT       NOT NULL REFERENCES ranking_snapshots (generation) ON DELETE CASCADE,
    rank        INTEGER      NOT NULL,
    video_id    VARCHAR(32)  NOT NULL,
    score       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (generation, rank)
);

-- A/B experiments. A device's variant is decided once and stored (sticky): later changes
-- to the split don't move devices between variants mid-experiment.
CREATE TABLE experiments (
    key                VARCHAR(50)  PRIMARY KEY,
    enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    treatment_percent  INTEGER      NOT NULL DEFAULT 50 CHECK (treatment_percent BETWEEN 0 AND 100),
    description        VARCHAR(200) NOT NULL DEFAULT '',
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TABLE experiment_exposures (
    experiment_key  VARCHAR(50)  NOT NULL REFERENCES experiments (key) ON DELETE CASCADE,
    device_id       VARCHAR(64)  NOT NULL,
    variant         VARCHAR(20)  NOT NULL CHECK (variant IN ('control', 'treatment')),
    first_exposed   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (experiment_key, device_id)
);

-- Control: the feed newest first. Treatment: "For You" ranking. Starts at 50/50.
INSERT INTO experiments (key, enabled, treatment_percent, description) VALUES
    ('feed_ranking', TRUE, 50, 'Control: newest first. Treatment: For You ranking.');
