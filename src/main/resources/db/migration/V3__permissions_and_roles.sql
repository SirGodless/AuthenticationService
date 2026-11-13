-- Rechte und Rollen mit den eingebauten Rechten und der Rolle Admin (Struktur nach CLAUDE.md Abschnitt 5).
-- Die Auswertung kommt in Etappe 4.

CREATE TABLE permissions (
    key             text PRIMARY KEY,
    type            text NOT NULL CHECK (type IN ('boolean', 'number')),
    -- nur für Zahlenrechte: 'higher' = höher gibt mehr Rechte, 'lower' = niedriger gibt mehr
    direction       text CHECK (direction IN ('higher', 'lower')),
    allow_unlimited boolean NOT NULL DEFAULT false,
    link_grantable  boolean NOT NULL DEFAULT false,
    builtin         boolean NOT NULL DEFAULT false,
    description     text NOT NULL DEFAULT '',
    CHECK ((type = 'number') = (direction IS NOT NULL)),
    CHECK (NOT allow_unlimited OR direction = 'higher')
);

CREATE TABLE roles (
    id        uuid PRIMARY KEY,
    name      text NOT NULL UNIQUE,
    builtin   boolean NOT NULL DEFAULT false,
    -- true: die Rolle enthält implizit alle Rechte mit Präfix 'admin.', auch später neue
    all_admin_permissions boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id),
    role_id uuid NOT NULL REFERENCES roles (id),
    PRIMARY KEY (user_id, role_id)
);

INSERT INTO permissions (key, type, direction, allow_unlimited, builtin, description) VALUES
    ('admin.users.view',         'boolean', NULL, false, true, 'Benutzer ansehen'),
    ('admin.users.ban',          'boolean', NULL, false, true, 'Benutzer sperren und entsperren'),
    ('admin.users.reset_2fa',    'boolean', NULL, false, true, '2FA eines Benutzers zurücksetzen'),
    ('admin.roles.manage',       'boolean', NULL, false, true, 'Rollen verwalten'),
    ('admin.permissions.manage', 'boolean', NULL, false, true, 'Rechte verwalten'),
    ('admin.services.manage',    'boolean', NULL, false, true, 'Dienste und Clients verwalten'),
    ('admin.links.view_all',     'boolean', NULL, false, true, 'Alle Einladungslinks sehen'),
    ('admin.audit.view',         'boolean', NULL, false, true, 'Protokoll ansehen'),
    ('admin.settings.manage',    'boolean', NULL, false, true, 'Einstellungen verwalten'),
    ('links.create',             'boolean', NULL, false, true, 'Einladungslinks erzeugen'),
    ('links.max_active',         'number', 'higher', true, true, 'Maximal gleichzeitig aktive Einladungslinks');

-- Eingebaute Rolle Admin. Sie gibt von sich aus keinen Zugang zu Diensten.
INSERT INTO roles (id, name, builtin, all_admin_permissions)
VALUES ('00000000-0000-0000-0000-000000000001', 'Admin', true, true);
