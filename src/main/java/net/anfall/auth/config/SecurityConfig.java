package net.anfall.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // Untergrenzen laut OWASP Password Storage Cheat Sheet. Darunter startet der Dienst nicht.
    private static final int MIN_MEMORY_KIB = 19 * 1024;
    private static final int MIN_ITERATIONS = 2;

    @Bean
    PasswordEncoder passwordEncoder(AppProperties props) {
        AppProperties.Argon2 a = props.argon2();
        if (a.memoryKib() < MIN_MEMORY_KIB || a.iterations() < MIN_ITERATIONS || a.parallelism() < 1) {
            throw new IllegalStateException("Argon2-Einstellungen liegen unter der OWASP-Mindestkonfiguration "
                    + "(19 MiB Speicher, 2 Durchläufe, Parallelität 1)");
        }
        // 16 Byte Salt, 32 Byte Hash. Das Format enthält die Parameter, ein späteres Erhöhen bricht nichts.
        return new Argon2PasswordEncoder(16, 32, a.parallelism(), a.memoryKib(), a.iterations());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Das Setup wird allein durch den geheimen Token im Pfad geschützt, nicht durch ein Cookie.
                // Es gibt keine Sitzung, die ein fremder Browser mitschicken könnte, also kein CSRF-Risiko.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/setup/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/setup/**").permitAll()
                        // Health läuft auf dem internen Management-Port 8081 und ist von außen nicht erreichbar.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
