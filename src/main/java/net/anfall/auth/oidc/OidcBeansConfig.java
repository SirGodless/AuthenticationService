package net.anfall.auth.oidc;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import net.anfall.auth.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * Die Bausteine des Providers, die auch ohne Webserver gebraucht werden (Betreiber-Befehle legen z. B. Dienste an):
 * Client- und Autorisierungsspeicher, Signierschlüssel, Einstellungen. Die Filterkette steht in
 * {@link OidcAuthorizationServerConfig} und gibt es nur im Webbetrieb.
 */
@Configuration
class OidcBeansConfig {

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbc) {
        return new JdbcRegisteredClientRepository(jdbc);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    /** Die Issuer-Adresse ist fest (https://auth.anfall.net) und darf sich nie ändern, sie steht in allen Clients. */
    @Bean
    AuthorizationServerSettings authorizationServerSettings(AppProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.publicBaseUrl()).build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeyService keys) {
        return (selector, context) -> selector.select(keys.loadJwkSet());
    }

    /**
     * Signiert mit dem einzigen Schlüssel, der einen privaten Teil hat (dem aktiven). Ausgemusterte Schlüssel stehen nur
     * öffentlich im JWKS. Ohne diese Regel weigert sich der Encoder, sobald nach einer Rotation mehrere Schlüssel da sind.
     */
    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        encoder.setJwkSelector(keys -> keys.stream().filter(JWK::isPrivate).findFirst()
                .orElseThrow(() -> new IllegalStateException("Kein aktiver Signierschlüssel vorhanden")));
        return encoder;
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return NimbusJwtDecoder.withJwkSource(jwkSource).build();
    }
}
