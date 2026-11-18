-- Dienste (Vaultwarden, Audiobookshelf, ...): Anzeige im Hub und das Recht, das den Zugang regelt.
-- Der OIDC-Client dazu steht in oauth2_registered_client und ist über client_id verknüpft.
CREATE TABLE services (
    key                 text PRIMARY KEY CHECK (key ~ '^[a-z0-9][a-z0-9_-]{1,31}$'),
    display_name        text NOT NULL,
    client_id           text NOT NULL UNIQUE REFERENCES oauth2_registered_client (client_id),
    -- Dieses Recht braucht man für den Zugang (standardmäßig service.<key>)
    required_permission text NOT NULL REFERENCES permissions (key),
    -- new_tab: Link in neuem Tab, embedded: später eingebettet (Abschnitt 6 der Spezifikation)
    open_mode           text NOT NULL DEFAULT 'new_tab' CHECK (open_mode IN ('new_tab', 'embedded')),
    health_url          text,
    created_at          timestamptz NOT NULL DEFAULT now()
);
