-- Creator uploads and their processing, a state machine:
--   pending_upload -> uploaded -> processing -> ready
--                                            \-> failed
-- The table is also the work queue: workers claim the oldest 'uploaded' row (or a
-- 'processing' row whose lease ran out, i.e. a crashed worker) with FOR UPDATE SKIP LOCKED.
CREATE TABLE uploads (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title         VARCHAR(200) NOT NULL,
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    object_key    VARCHAR(200) NOT NULL,                 -- the original, in object storage
    status        VARCHAR(20)  NOT NULL
                  CHECK (status IN ('pending_upload', 'uploaded', 'processing', 'ready', 'failed')),
    attempts      INTEGER      NOT NULL DEFAULT 0,
    lease_until   TIMESTAMPTZ,                           -- while 'processing'
    error         VARCHAR(300),
    video_id      VARCHAR(32)  REFERENCES videos (id) ON DELETE SET NULL,  -- once 'ready'
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_uploads_user ON uploads (user_id, created_at DESC);
CREATE INDEX ix_uploads_queue ON uploads (status, updated_at) WHERE status IN ('uploaded', 'processing');

INSERT INTO remote_config (key, enabled, rollout_percent, value, description) VALUES
    ('uploads', TRUE, 100, NULL, 'Creators can post clips (kill switch for the upload pipeline)');
