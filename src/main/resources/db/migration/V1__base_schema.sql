-- Etappe 1: Grundschema.
-- Personenbezogene Daten (E-Mail) und Zugangsdaten (Passwort, TOTP) liegen in eigenen
-- Tabellen, getrennt von users (UUID, Username). So lässt sich später anonymisieren,
-- ohne die UUID und verknüpfte Einträge zu verlieren (CLAUDE.md Abschnitt 7).

CREATE TABLE users (
    id          uuid PRIMARY KEY,
    -- Immer klein geschrieben gespeichert. Bleibt auch nach einer Löschung belegt.
    username    text NOT NULL UNIQUE CHECK (username = lower(username)),
    language    text NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'de')),
    banned      boolean NOT NULL DEFAULT false,
    invited_by  uuid REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_pii (
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    email          text NOT NULL UNIQUE CHECK (email = lower(email)),
    email_verified boolean NOT NULL DEFAULT false
);

CREATE TABLE user_credentials (
    user_id       uuid PRIMARY KEY REFERENCES users (id),
    password_hash text NOT NULL,
    changed_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE user_totp (
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    -- AES-GCM verschlüsselt (IV + Ciphertext), Schlüssel aus der Umgebung
    secret_enc     bytea NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- verhindert, dass derselbe Code zweimal benutzt wird
    last_used_step bigint
);

CREATE TABLE recovery_codes (
    id        uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id),
    -- SHA-256 des Codes (Codes haben 80 Bit Zufall, ein langsamer Hash bringt nichts)
    code_hash text NOT NULL UNIQUE,
    used_at   timestamptz
);

-- Rechte (Struktur nach Abschnitt 5). Die Auswertung kommt in Etappe 4.
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

-- Setup-Link für den ersten Admin. Nur der Hash des Tokens wird gespeichert.
CREATE TABLE setup_tokens (
    id                 uuid PRIMARY KEY,
    token_hash         text NOT NULL UNIQUE,
    created_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    used_at            timestamptz,
    -- TOTP-Geheimnis, das der Setup-Seite angezeigt wurde (verschlüsselt)
    pending_totp_enc   bytea
);

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
