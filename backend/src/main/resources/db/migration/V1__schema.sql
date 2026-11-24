CREATE TABLE videos (
    id            VARCHAR(32)  PRIMARY KEY,
    title         VARCHAR(200) NOT NULL,
    creator       VARCHAR(80)  NOT NULL,
    duration_ms   INTEGER      NOT NULL,
    width         INTEGER      NOT NULL,
    height        INTEGER      NOT NULL,
    hls_path      VARCHAR(300) NOT NULL,   -- relative to MEDIA_BASE_URL, e.g. v001/index.m3u8
    poster_path   VARCHAR(300) NOT NULL,
    published_at  TIMESTAMPTZ  NOT NULL,
    like_count    INTEGER      NOT NULL DEFAULT 0
);

-- Keyset pagination walks (published_at DESC, id DESC).
CREATE INDEX ix_videos_feed_order ON videos (published_at DESC, id DESC);

CREATE TABLE likes (
    video_id    VARCHAR(32) NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    device_id   VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, device_id)
);

-- event_id is generated on the device, so a batch the app retries after a
-- timeout is stored once: the primary key turns the duplicate into a no-op.
CREATE TABLE playback_events (
    event_id     VARCHAR(36) PRIMARY KEY,
    video_id     VARCHAR(32) NOT NULL,
    type         VARCHAR(20) NOT NULL,
    value_ms     BIGINT,
    preload      BOOLEAN,
    device_id    VARCHAR(64) NOT NULL,
    session_id   VARCHAR(64) NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_events_type_preload ON playback_events (type, preload);
CREATE INDEX ix_events_video_type ON playback_events (video_id, type);
