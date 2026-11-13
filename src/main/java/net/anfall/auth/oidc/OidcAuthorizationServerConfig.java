package net.anfall.auth.oidc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/**
 * Der OIDC-Provider (Spring Authorization Server): Discovery, Authorize, Token, JWKS, UserInfo, Refresh.
 * Läuft in einer eigenen Filterkette vor der Kette der API (Order 1) und gilt nur für die Provider-Adressen
 * (/oauth2/*, /.well-known/*, /connect/*, /userinfo).
 * Der Token-Customizer ({@link OidcTokenCustomizer}) wird als Bean gefunden, weil die Klasse ihn selbst implementiert.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OidcAuthorizationServerConfig {

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerFilterChain(HttpSecurity http, OidcSecurityContextRepository contexts,
                                                       LoginRedirectEntryPoint loginRedirect,
                                                       ServicePermissionFilter permissionFilter) throws Exception {
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.oidc(Customizer.withDefaults());   // OIDC ist standardmäßig aus und muss eingeschaltet werden
        });
        http
                .securityContext(c -> c.securityContextRepository(contexts))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(loginRedirect))
                // Der UserInfo-Endpunkt nimmt Access-Tokens an und braucht dafür einen JwtDecoder
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                // Rechteprüfung vor dem Authorize-Endpunkt: ohne Recht kein Code
                .addFilterAfter(permissionFilter, SecurityContextHolderFilter.class);
        return http.build();
    }
}
