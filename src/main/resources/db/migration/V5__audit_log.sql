-- Protokoll: wird nur angehängt. Nie Passwörter, Tokens, Geheimnisse oder Codes eintragen.
CREATE TABLE audit_log (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at         timestamptz NOT NULL DEFAULT now(),
    actor_id   uuid REFERENCES users (id),
    subject_id uuid REFERENCES users (id),
    action     text NOT NULL,
    details    jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- wird nach 90 Tagen geleert (Aufgabe ab Etappe 2)
    ip         text
);
CREATE INDEX audit_log_subject_idx ON audit_log (subject_id, at);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, at);
