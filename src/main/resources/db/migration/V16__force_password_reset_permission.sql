-- Neues eingebautes Verwaltungsrecht: Passwort-Reset für einen User erzwingen (NIST SP 800-63B-4: Wechsel erzwingen
-- bei Hinweis auf Kompromittierung). Die Rolle Admin enthält es automatisch, weil sie alle admin.*-Rechte enthält.
INSERT INTO permissions (key, type, direction, allow_unlimited, builtin, description)
VALUES ('admin.users.force_password_reset', 'boolean', NULL, false, true, 'Passwort-Reset für einen User erzwingen');
