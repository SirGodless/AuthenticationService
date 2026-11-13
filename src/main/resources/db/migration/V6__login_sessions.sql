-- Eigene Sitzungstabelle statt Spring Session: So lassen sich Sitzungen eines Users gezielt beenden
-- (Bann, Passwort-Reset) und die Laufzeiten (12 Stunden oder 30 Tage) genau steuern.
CREATE TABLE login_sessions (
    -- SHA-256 des Cookie-Wertes. Der Cookie-Wert selbst steht nie in der Datenbank.
    id_hash                text PRIMARY KEY,
    user_id                uuid NOT NULL REFERENCES users (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    expires_at             timestamptz NOT NULL,
    remember               boolean NOT NULL,
    -- Passwort stimmt, die 2FA fehlt noch. Eine solche Sitzung zählt nicht als angemeldet.
    second_factor_pending  boolean NOT NULL DEFAULT false
);
CREATE INDEX login_sessions_user_idx ON login_sessions (user_id);
CREATE INDEX login_sessions_expires_idx ON login_sessions (expires_at);
