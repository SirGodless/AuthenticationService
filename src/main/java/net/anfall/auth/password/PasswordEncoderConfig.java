package net.anfall.auth.password;

import net.anfall.auth.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Passwort-Hashing mit Argon2id. */
@Configuration
class PasswordEncoderConfig {

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
}
