-- Sperre pro IP für den Link-Endpunkt nach zu vielen falschen Links (CLAUDE.md Abschnitt 4).
-- Eigene Tabelle statt des allgemeinen Zählers, damit der Admin die gesperrten Adressen sehen und aufheben kann.
CREATE TABLE invite_ip_blocks (
    ip_key        text PRIMARY KEY,
    fail_count    integer NOT NULL DEFAULT 0,
    window_start  timestamptz NOT NULL DEFAULT now(),
    blocked_until timestamptz,
    blocked_at    timestamptz
);
