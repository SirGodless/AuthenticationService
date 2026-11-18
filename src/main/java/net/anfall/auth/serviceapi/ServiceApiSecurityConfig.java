package net.anfall.auth.serviceapi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Eigene Filterkette für die Dienst-Schnittstelle (/api/service/*). Ein Dienst ruft sie mit einem Bearer-Token auf, das er
 * per client_credentials vom Provider holt (Scope "service"). Keine Cookies, kein CSRF-Schutz nötig (es gibt keine Sitzung).
 * Ob das Token wirklich von einem Dienst stammt und kein User-Token ist, prüft {@link ServiceCaller}.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ServiceApiSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain serviceApiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/service/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_service"))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
