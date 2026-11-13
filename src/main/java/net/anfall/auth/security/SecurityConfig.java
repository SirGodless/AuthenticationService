package net.anfall.auth.security;

import net.anfall.auth.login.session.SessionSecurityContextRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** Wer darf welche Adresse aufrufen, CSRF-Schutz und die Anbindung der Sitzungen. */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SessionSecurityContextRepository contexts) throws Exception {
        // CSRF-Schutz für alles, was eine Sitzung nutzt: Der Token steht in einem lesbaren Cookie,
        // die UI schickt ihn als Header X-XSRF-TOKEN zurück. Ein fremder Browser-Tab kennt ihn nicht.
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.secure(true).sameSite("Lax").path("/"));

        http
                .securityContext(c -> c.securityContextRepository(contexts))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Das Setup wird allein durch den geheimen Token im Pfad geschützt, nicht durch ein Cookie.
                        .ignoringRequestMatchers("/api/setup/**"))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/setup/**").permitAll()
                        // Öffentlich: Anmelden, Mail-Links, Passwort vergessen. Der Rest braucht eine Sitzung.
                        .requestMatchers("/api/auth/me", "/api/auth/login", "/api/auth/login/**",
                                "/api/auth/confirm-email", "/api/auth/confirm-email/**",
                                "/api/auth/resend-confirmation",
                                "/api/auth/password-reset/**").permitAll()
                        // Health läuft auf dem internen Management-Port 8081 und ist von außen nicht erreichbar.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }
}
