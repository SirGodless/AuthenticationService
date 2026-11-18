-- Einladungslinks (CLAUDE.md Abschnitt 4). Links werden nie gelöscht, nur deaktiviert.
-- Der Token steht nur als Hash in der Datenbank. role_id hat bewusst keinen Fremdschlüssel: Eine Rolle darf gelöscht
-- werden, der Link bleibt als Nachweis erhalten (Rollenname und Rechte zum Zeitpunkt der Erstellung stehen daneben).
CREATE TABLE invite_links (
    id                 uuid PRIMARY KEY,
    token_hash         text NOT NULL UNIQUE,
    creator_id         uuid NOT NULL REFERENCES users (id),
    role_id            uuid,
    role_name          text,
    -- aufgelöste Rechte zum Zeitpunkt der Erstellung: [{"key":..,"number":..,"unlimited":..}]
    role_permissions   jsonb NOT NULL DEFAULT '[]',
    direct_permissions jsonb NOT NULL DEFAULT '[]',
    -- true bei Rolle oder Rechten: kürzere Höchstdauer
    privileged         boolean NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz NOT NULL,
    status             text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'used', 'deactivated')),
    -- bei deactivated: manual, admin, creator_banned
    status_reason      text,
    status_changed_at  timestamptz,
    used_by            uuid REFERENCES users (id),
    -- was beim Einlösen tatsächlich vergeben wurde
    granted            jsonb
);
CREATE INDEX invite_links_creator_idx ON invite_links (creator_id, status);
