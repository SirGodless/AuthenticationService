-- Anzeige der Dienste im Hub: Beschreibung, Adresse zum Öffnen und eine eigene Akzentfarbe je Dienst.
-- Die Farbe stellt vorerst der Admin im Dashboard ein. Später sollen Dienste sie selbst melden können.
ALTER TABLE services
    ADD COLUMN description  text NOT NULL DEFAULT '',
    ADD COLUMN url          text,
    ADD COLUMN accent_color text CHECK (accent_color ~ '^#[0-9a-f]{6}$');
