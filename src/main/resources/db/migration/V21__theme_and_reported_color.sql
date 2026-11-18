-- Darstellung als Einstellung des Kontos (system = Einstellung des Geräts) und Farbe, die ein Dienst später selbst melden kann.
ALTER TABLE users ADD COLUMN theme text NOT NULL DEFAULT 'system' CHECK (theme IN ('system', 'light', 'dark'));

-- reported_accent_color: vom Dienst gemeldet (noch gibt es keinen Weg dafür, die Spalte ist vorbereitet).
-- accent_locked: true heißt, die im Hub eingestellte Farbe bleibt immer, auch wenn der Dienst eine meldet.
ALTER TABLE services
    ADD COLUMN reported_accent_color text CHECK (reported_accent_color ~ '^#[0-9a-f]{6}$'),
    ADD COLUMN accent_locked boolean NOT NULL DEFAULT false;
