-- Konten: Username, Kontaktdaten, Zugangsdaten.
-- Personenbezogene Daten (E-Mail) und Zugangsdaten (Passwort) liegen in eigenen Tabellen, getrennt von users
-- (UUID, Username). So lässt sich später anonymisieren, ohne die UUID und verknüpfte Einträge zu verlieren
-- (CLAUDE.md Abschnitt 7).

CREATE TABLE users (
    id          uuid PRIMARY KEY,
    -- Immer klein geschrieben gespeichert. Bleibt auch nach einer Löschung belegt.
    username    text NOT NULL UNIQUE CHECK (username = lower(username)),
    language    text NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'de')),
    banned      boolean NOT NULL DEFAULT false,
    invited_by  uuid REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_pii (
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    email          text NOT NULL UNIQUE CHECK (email = lower(email)),
    email_verified boolean NOT NULL DEFAULT false
);

CREATE TABLE user_credentials (
    user_id       uuid PRIMARY KEY REFERENCES users (id),
    password_hash text NOT NULL,
    changed_at    timestamptz NOT NULL DEFAULT now()
);
