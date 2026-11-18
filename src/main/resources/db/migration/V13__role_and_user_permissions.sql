-- Welche Rechte eine Rolle enthält und welche ein User direkt bekommt. Vorerst nur boolesche Rechte:
-- Ein Eintrag heißt "Recht vergeben". Zahlenrechte mit Richtung und "unbegrenzt" kommen mit Etappe 4
-- (zusätzliche Spalten in einer späteren Migration).
CREATE TABLE role_permissions (
    role_id        uuid NOT NULL REFERENCES roles (id),
    permission_key text NOT NULL REFERENCES permissions (key),
    PRIMARY KEY (role_id, permission_key)
);

CREATE TABLE user_permissions (
    user_id        uuid NOT NULL REFERENCES users (id),
    permission_key text NOT NULL REFERENCES permissions (key),
    PRIMARY KEY (user_id, permission_key)
);
