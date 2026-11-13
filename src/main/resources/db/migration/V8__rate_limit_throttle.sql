-- Zähler für Limits (Login-Fehlversuche, Mail-Anfragen). Der Schlüssel ist ein Hash,
-- damit hier keine E-Mail-Adressen oder IPs im Klartext liegen.
CREATE TABLE throttle (
    bucket        text NOT NULL,
    key_hash      text NOT NULL,
    count         integer NOT NULL DEFAULT 0,
    window_start  timestamptz NOT NULL DEFAULT now(),
    locked_until  timestamptz,
    PRIMARY KEY (bucket, key_hash)
);
