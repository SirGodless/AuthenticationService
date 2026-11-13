package net.anfall.auth.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Beim Start den ersten Signierschlüssel anlegen, stündlich abgelaufene ausgemusterte Schlüssel löschen. */
@Component
@Order(0)
@ConditionalOnExpression("!${app.cli.enabled:false}")
public class SigningKeyJobs implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyJobs.class);
    private final SigningKeyService keys;

    public SigningKeyJobs(SigningKeyService keys) {
        this.keys = keys;
    }

    @Override
    public void run(ApplicationArguments args) {
        keys.ensureActiveKey();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int deleteExpired() {
        int deleted = keys.deleteExpiredRetiredKeys();
        log.info("Signierschlüssel: {} abgelaufene ausgemusterte gelöscht", deleted);
        return deleted;
    }
}
