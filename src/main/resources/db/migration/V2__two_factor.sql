-- Zweiter Faktor: TOTP-Geheimnis und Wiederherstellungscodes.

CREATE TABLE user_totp (
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    -- AES-GCM verschlüsselt (IV + Ciphertext), Schlüssel aus der Umgebung
    secret_enc     bytea NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- verhindert, dass derselbe Code zweimal benutzt wird
    last_used_step bigint
);

CREATE TABLE recovery_codes (
    id        uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id),
    -- SHA-256 des Codes (Codes haben 120 Bit Zufall, ein langsamer Hash bringt nichts)
    code_hash text NOT NULL UNIQUE,
    used_at   timestamptz
);
