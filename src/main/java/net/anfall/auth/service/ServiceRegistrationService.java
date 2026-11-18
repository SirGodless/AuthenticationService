package net.anfall.auth.service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Legt einen Dienst an: das Recht service.&lt;key&gt;, den OIDC-Client und den Eintrag im Hub, in einem Schritt.
 * Jeder Client verlangt ein Recht (Abschnitt 6 der Spezifikation), deshalb gibt es keinen Client ohne Dienst.
 */
@Service
public class ServiceRegistrationService {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{1,31}");

    /** Scope des Tokens, mit dem ein Dienst die Dienst-Schnittstelle (/api/service/*) aufruft. */
    public static final String SERVICE_SCOPE = "service";

    /** Der angelegte Dienst und das Client-Geheimnis im Klartext (wird nur jetzt einmal angezeigt). */
    public record RegisteredService(ServiceInfo service, String clientSecret) {
    }

    private final ServiceRepository services;
    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final AppProperties.Oidc oidc;

    public ServiceRegistrationService(ServiceRepository services, RegisteredClientRepository clients,
                                      PasswordEncoder passwordEncoder, JdbcClient jdbc, AppProperties props) {
        this.services = services;
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.oidc = props.oidc();
    }

    /**
     * @param key         kurzer Name, wird zum Recht "service.&lt;key&gt;" und zur Client-ID
     * @param redirectUris erlaubte Rücksprung-Adressen, genau so wie sie der Dienst sendet
     */
    @Transactional
    public RegisteredService register(String key, String displayName, List<String> redirectUris) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_key_invalid");
        }
        if (displayName == null || displayName.isBlank() || redirectUris == null || redirectUris.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_invalid");
        }
        if (services.findByKey(key).isPresent() || clients.findByClientId(key) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "service_exists");
        }
        String permission = "service." + key;
        jdbc.sql("""
                        INSERT INTO permissions (key, type, builtin, description, link_grantable)
                        VALUES (:k, 'boolean', false, :d, false)
                        ON CONFLICT (key) DO NOTHING
                        """)
                .param("k", permission).param("d", "Zugang zum Dienst " + displayName).update();

        String secret = Tokens.newToken();
        RegisteredClient.Builder client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(key)
                .clientName(displayName)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                // client_credentials: Der Dienst selbst (nicht ein User) holt sich damit ein Token für die Dienst-Schnittstelle
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(OidcScopes.EMAIL).scope("offline_access")
                .scope(SERVICE_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)                  // PKCE für alle Clients
                        .requireAuthorizationConsent(false)     // eigene Dienste, keine Zustimmungsseite
                        .build())
                .tokenSettings(tokenSettings());
        redirectUris.stream().map(ServiceRegistrationService::cleanRedirectUri).forEach(client::redirectUri);
        clients.save(client.build());

        ServiceInfo service = new ServiceInfo(key, displayName, key, permission, "new_tab", null, "", null, null, null, false);
        services.insert(service);
        return new RegisteredService(service, secret);
    }

    /** Ersetzt die erlaubten Rücksprung-Adressen eines Dienstes. */
    @Transactional
    public void replaceRedirectUris(String key, List<String> redirectUris) {
        RegisteredClient existing = clientOf(key);
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "redirect_uris_invalid");
        }
        List<String> cleaned = redirectUris.stream().map(ServiceRegistrationService::cleanRedirectUri).distinct().toList();
        clients.save(RegisteredClient.from(existing).redirectUris(set -> {
            set.clear();
            set.addAll(cleaned);
        }).build());
    }

    /** Neues Client-Geheimnis. Das alte gilt sofort nicht mehr (schon ausgestellte Tokens bleiben gültig). Gibt das neue im Klartext zurück. */
    @Transactional
    public String regenerateSecret(String key) {
        RegisteredClient existing = clientOf(key);
        String secret = Tokens.newToken();
        clients.save(RegisteredClient.from(existing).clientSecret(passwordEncoder.encode(secret)).build());
        return secret;
    }

    public List<String> redirectUrisOf(String key) {
        return List.copyOf(clientOf(key).getRedirectUris());
    }

    private RegisteredClient clientOf(String key) {
        RegisteredClient client = clients.findByClientId(key);
        if (client == null || services.findByKey(key).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "service_unknown");
        }
        return client;
    }

    /**
     * Rücksprung-Adressen: https, http nur für localhost, oder ein eigenes Schema einer App (z. B. audiobookshelf://oauth).
     * Keine Platzhalter, kein Fragment, nicht länger als 300 Zeichen, und nie javascript:, data: oder file:.
     */
    static String cleanRedirectUri(String value) {
        String v = value == null ? "" : value.trim();
        try {
            java.net.URI uri = java.net.URI.create(v);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            boolean forbidden = List.of("javascript", "data", "file", "vbscript", "about", "blob", "ftp").contains(scheme);
            boolean web = scheme.equals("https")
                    || (scheme.equals("http") && List.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost())));
            boolean app = scheme.matches("[a-z][a-z0-9+.-]{2,30}") && !scheme.equals("http") && !scheme.equals("https") && !forbidden;
            boolean ok = v.length() <= 300 && !v.contains("*") && uri.getFragment() == null && !forbidden
                    && ((web && uri.getHost() != null) || app);
            if (!ok) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "redirect_uri_invalid");
            }
            return v;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "redirect_uri_invalid");
        }
    }

    private TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .accessTokenTimeToLive(oidc.accessTokenTtl())
                .refreshTokenTimeToLive(oidc.refreshTokenTtl())
                .reuseRefreshTokens(false)                      // Rotation: jeder Refresh-Token gilt nur einmal
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .authorizationCodeTimeToLive(Duration.ofMinutes(2))
                .build();
    }
}
