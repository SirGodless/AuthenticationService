-- Einstellungen, die der Admin im Dashboard ändert (zuerst die Grenzen der Einladungslinks).
CREATE TABLE settings (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
