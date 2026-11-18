-- OIDC-Provider: Tabellen von Spring Authorization Server (Clients und Autorisierungen mit Tokens).
-- Aufbau nach den mitgelieferten Schema-Dateien von Spring, für PostgreSQL angepasst (blob wird text,
-- timestamp wird timestamptz, wie in deren Hinweis beschrieben). Die Zustimmungstabelle fehlt bewusst:
-- Unsere Dienste sind eigene Anwendungen, es gibt keine Zustimmungsseite.

CREATE TABLE oauth2_registered_client (
    id                            varchar(100) NOT NULL,
    client_id                     varchar(100) NOT NULL,
    client_id_issued_at           timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret                 varchar(200) DEFAULT NULL,
    client_secret_expires_at      timestamptz DEFAULT NULL,
    client_name                   varchar(200) NOT NULL,
    client_authentication_methods varchar(1000) NOT NULL,
    authorization_grant_types     varchar(1000) NOT NULL,
    redirect_uris                 varchar(1000) DEFAULT NULL,
    post_logout_redirect_uris     varchar(1000) DEFAULT NULL,
    scopes                        varchar(1000) NOT NULL,
    client_settings               varchar(2000) NOT NULL,
    token_settings                varchar(2000) NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX oauth2_registered_client_client_id_idx ON oauth2_registered_client (client_id);

CREATE TABLE oauth2_authorization (
    id                            varchar(100) NOT NULL,
    registered_client_id          varchar(100) NOT NULL,
    -- Die User-UUID. Danach wird gesucht, wenn alle Tokens eines Users widerrufen werden (Bann, Reset).
    principal_name                varchar(200) NOT NULL,
    authorization_grant_type      varchar(100) NOT NULL,
    authorized_scopes             varchar(1000) DEFAULT NULL,
    attributes                    text DEFAULT NULL,
    state                         varchar(500) DEFAULT NULL,
    authorization_code_value      text DEFAULT NULL,
    authorization_code_issued_at  timestamptz DEFAULT NULL,
    authorization_code_expires_at timestamptz DEFAULT NULL,
    authorization_code_metadata   text DEFAULT NULL,
    access_token_value            text DEFAULT NULL,
    access_token_issued_at        timestamptz DEFAULT NULL,
    access_token_expires_at       timestamptz DEFAULT NULL,
    access_token_metadata         text DEFAULT NULL,
    access_token_type             varchar(100) DEFAULT NULL,
    access_token_scopes           varchar(1000) DEFAULT NULL,
    oidc_id_token_value           text DEFAULT NULL,
    oidc_id_token_issued_at       timestamptz DEFAULT NULL,
    oidc_id_token_expires_at      timestamptz DEFAULT NULL,
    oidc_id_token_metadata        text DEFAULT NULL,
    refresh_token_value           text DEFAULT NULL,
    refresh_token_issued_at       timestamptz DEFAULT NULL,
    refresh_token_expires_at      timestamptz DEFAULT NULL,
    refresh_token_metadata        text DEFAULT NULL,
    user_code_value               text DEFAULT NULL,
    user_code_issued_at           timestamptz DEFAULT NULL,
    user_code_expires_at          timestamptz DEFAULT NULL,
    user_code_metadata            text DEFAULT NULL,
    device_code_value             text DEFAULT NULL,
    device_code_issued_at         timestamptz DEFAULT NULL,
    device_code_expires_at        timestamptz DEFAULT NULL,
    device_code_metadata          text DEFAULT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX oauth2_authorization_principal_idx ON oauth2_authorization (principal_name);
