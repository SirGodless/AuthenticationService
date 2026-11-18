package net.anfall.auth.emailtoken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Löscht abgelaufene oder benutzte Mail-Tokens nach 7 Tagen. */
@Component
public class EmailTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(EmailTokenCleanupJob.class);
    private final JdbcClient jdbc;

    public EmailTokenCleanupJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int run() {
        int deleted = jdbc.sql("DELETE FROM email_tokens WHERE COALESCE(used_at, expires_at) < now() - interval '7 days'")
                .update();
        log.info("Mail-Tokens: {} alte gelöscht", deleted);
        return deleted;
    }
}
