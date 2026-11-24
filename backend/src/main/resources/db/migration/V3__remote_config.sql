-- Remote config and feature flags. Each row is a flag the app and server read at run time.
--   enabled          the kill switch: FALSE turns the feature off for everyone, at once
--   rollout_percent  gradual rollout: on for this share of devices (stable per device)
--   value            optional setting the feature reads (a number or short string)
CREATE TABLE remote_config (
    key              VARCHAR(64)  PRIMARY KEY,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    rollout_percent  INTEGER      NOT NULL DEFAULT 100 CHECK (rollout_percent BETWEEN 0 AND 100),
    value            VARCHAR(500),
    description      VARCHAR(200) NOT NULL DEFAULT '',
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO remote_config (key, enabled, rollout_percent, value, description) VALUES
    ('sponsored_clips',        TRUE, 100, NULL,  'Sponsored clips in the feed (server side)'),
    ('sponsored_slot',         TRUE, 100, '5',   'Clips shown before the sponsored one'),
    ('preload',                TRUE, 100, NULL,  'Preload upcoming clips in the app'),
    ('share_cards',            TRUE, 100, NULL,  'Attach a poster image when sharing'),
    ('push_notifications',     TRUE, 100, NULL,  'New-clip push notifications'),
    ('config_refresh_seconds', TRUE, 100, '300', 'How often the app may fetch this config');
