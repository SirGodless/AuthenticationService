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
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(OidcScopes.EMAIL).scope("offline_access")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)                  // PKCE für alle Clients
                        .requireAuthorizationConsent(false)     // eigene Dienste, keine Zustimmungsseite
                        .build())
                .tokenSettings(tokenSettings());
        redirectUris.forEach(client::redirectUri);
        clients.save(client.build());

        ServiceInfo service = new ServiceInfo(key, displayName, key, permission, "new_tab", null);
        services.insert(service);
        return new RegisteredService(service, secret);
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
