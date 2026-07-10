-- Self-service "forgot password" flow: one row per outstanding reset request.
-- Tokens are opaque random strings (not JWTs — OPAC has no JWT infra), single-use,
-- short-lived, and looked up directly rather than decoded.
CREATE TABLE IF NOT EXISTS password_reset_token (
    uuid               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_uuid          UUID NOT NULL REFERENCES user_master(uuid) ON DELETE CASCADE,
    token              VARCHAR(255) NOT NULL UNIQUE,
    expiry_timestamp   TIMESTAMP NOT NULL,
    used               BOOLEAN NOT NULL DEFAULT FALSE,
    created_timestamp  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_password_reset_token_user_uuid ON password_reset_token(user_uuid);
