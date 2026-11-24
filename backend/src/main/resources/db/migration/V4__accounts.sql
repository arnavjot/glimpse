-- Creator accounts. The email is stored lower-cased (unique); only a BCrypt hash of
-- the password is kept.
CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    email          VARCHAR(254) NOT NULL UNIQUE,
    handle         VARCHAR(30)  NOT NULL UNIQUE,     -- shown as @handle on clips
    password_hash  VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Refresh tokens: only a SHA-256 of each token is stored, so a database leak can't be
-- replayed. Each use rotates the token (the old row is revoked and points at its
-- replacement); presenting an already-revoked token means it was copied, and every
-- session of that user is revoked.
CREATE TABLE refresh_tokens (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash   CHAR(64)     NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
