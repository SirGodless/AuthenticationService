-- 2FA einrichten (Etappe 4): Das Geheimnis, das der Seite gezeigt wurde, liegt hier, bis der erste Code bestätigt ist.
-- Erst die Bestätigung legt die 2FA wirklich an (user_totp). Eine neue Einrichtung ersetzt eine offene.
CREATE TABLE totp_enrollments (
    user_id    uuid PRIMARY KEY REFERENCES users (id),
    -- AES-GCM verschlüsselt, wie in user_totp
    secret_enc bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
