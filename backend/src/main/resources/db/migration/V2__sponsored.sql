-- Sponsored clips: inserted into feed pages per request (never cached with the page),
-- capped per device per day, rotated by what that device has seen least.
CREATE TABLE sponsored_clips (
    id           VARCHAR(32)  PRIMARY KEY,   -- e.g. ad001; never collides with a video id
    title        VARCHAR(200) NOT NULL,
    advertiser   VARCHAR(80)  NOT NULL,
    duration_ms  INTEGER      NOT NULL,
    width        INTEGER      NOT NULL,
    height       INTEGER      NOT NULL,
    hls_path     VARCHAR(300) NOT NULL,
    poster_path  VARCHAR(300) NOT NULL,
    cta_url      VARCHAR(500) NOT NULL,      -- "Learn more" opens this (http/https only)
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The frequency cap counts one device's recent ad impressions.
CREATE INDEX ix_events_device_type_time ON playback_events (device_id, type, occurred_at);
