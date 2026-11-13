-- Setup-Link für den ersten Admin. Nur der Hash des Tokens wird gespeichert.
CREATE TABLE setup_tokens (
    id                 uuid PRIMARY KEY,
    token_hash         text NOT NULL UNIQUE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    used_at            timestamptz,
    -- TOTP-Geheimnis, das der Setup-Seite angezeigt wurde (verschlüsselt)
    pending_totp_enc   bytea
);
