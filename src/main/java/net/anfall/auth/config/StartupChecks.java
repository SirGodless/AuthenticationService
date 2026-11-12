package net.anfall.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Prüft beim Start die Konfiguration und misst die Dauer eines Passwort-Hashs (Benchmark auf dem Zielsystem). */
@Component
@Order(0)
class StartupChecks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
    private final PasswordEncoder encoder;
    private final AppProperties props;

    StartupChecks(PasswordEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.publicBaseUrl() == null || !props.publicBaseUrl().startsWith("http")) {
            throw new IllegalStateException("PUBLIC_BASE_URL fehlt oder ist ungültig");
        }
        long start = System.nanoTime();
        encoder.encode("benchmark-only-not-a-real-password");
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("Argon2id: {} KiB, {} Durchläufe, Parallelität {}. Ein Hash dauert hier {} ms. "
                        + "Ziel ist grob 100 bis 500 ms, nicht niedriger als die OWASP-Mindestwerte.",
                props.argon2().memoryKib(), props.argon2().iterations(), props.argon2().parallelism(), ms);
    }
}
