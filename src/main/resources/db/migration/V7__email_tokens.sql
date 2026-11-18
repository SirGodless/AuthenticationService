-- Einmal-Links per Mail: E-Mail bestätigen und Passwort zurücksetzen.
CREATE TABLE email_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users (id),
    purpose     text NOT NULL CHECK (purpose IN ('confirm_email', 'password_reset')),
    token_hash  text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);
CREATE INDEX email_tokens_user_idx ON email_tokens (user_id, purpose);
