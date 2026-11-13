-- Etappe 2: Sitzungen, Mail-Tokens, Limits, Schutz des Protokolls.

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

-- Das Protokoll wird nur angehängt. Erlaubt ist allein das Leeren der IP-Adresse (nach 90 Tagen).
CREATE FUNCTION audit_log_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log ist nur zum Anhängen da';
    END IF;
    IF NEW.ip IS NOT NULL
       OR (NEW.id, NEW.at, NEW.actor_id, NEW.subject_id, NEW.action, NEW.details)
          IS DISTINCT FROM (OLD.id, OLD.at, OLD.actor_id, OLD.subject_id, OLD.action, OLD.details) THEN
        RAISE EXCEPTION 'audit_log darf nur durch Leeren der IP geändert werden';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_guard_trigger
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_guard();
