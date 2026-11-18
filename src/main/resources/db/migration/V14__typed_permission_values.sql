-- Rechte mit Werten (Etappe 4). Bisher bedeutete ein Eintrag "boolesches Recht vergeben". Jetzt können Einträge
-- auch Zahlenrechte tragen. Kein Eintrag heißt "nicht gesetzt" und ist nie dasselbe wie 0.
-- Ob ein Recht boolesch oder Zahl ist, steht in permissions.type (dort auch Richtung und "unbegrenzt erlaubt").
ALTER TABLE role_permissions
    ADD COLUMN number_value bigint CHECK (number_value >= 0),
    ADD COLUMN unlimited boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT role_permissions_value_check CHECK (NOT unlimited OR number_value IS NULL);

ALTER TABLE user_permissions
    ADD COLUMN number_value bigint CHECK (number_value >= 0),
    ADD COLUMN unlimited boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT user_permissions_value_check CHECK (NOT unlimited OR number_value IS NULL);
