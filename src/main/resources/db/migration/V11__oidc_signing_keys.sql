-- Signierschlüssel des OIDC-Providers. Mehrere Schlüssel gleichzeitig, damit sich der Schlüssel rotieren lässt:
-- Der aktive Schlüssel signiert, ältere bleiben im JWKS, bis alle damit signierten Tokens abgelaufen sind.
CREATE TABLE oidc_signing_keys (
    -- Schlüsselkennung (kid), steht in jedem Token und im JWKS
    kid          text PRIMARY KEY,
    -- Privater Schlüssel (PKCS#8), mit AES-256-GCM verschlüsselt. Leer, sobald der Schlüssel ausgemustert ist.
    private_key_enc bytea,
    -- Öffentlicher Schlüssel als JWK (JSON)
    public_jwk   text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- null = aktiv (signiert neue Tokens), sonst Zeitpunkt der Ausmusterung
    retired_at   timestamptz
);
-- Es gibt höchstens einen aktiven Schlüssel
CREATE UNIQUE INDEX oidc_signing_keys_one_active_idx ON oidc_signing_keys ((retired_at IS NULL)) WHERE retired_at IS NULL;
