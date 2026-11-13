package net.anfall.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** IP-Adressen im Protokoll werden nach 90 Tagen geleert. Der Eintrag selbst bleibt. */
@Component
public class AuditIpRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditIpRetentionJob.class);
    private final JdbcClient jdbc;

    public AuditIpRetentionJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int run() {
        int cleared = jdbc.sql("UPDATE audit_log SET ip = NULL WHERE ip IS NOT NULL AND at < now() - interval '90 days'")
                .update();
        log.info("Protokoll: {} IP-Adressen geleert", cleared);
        return cleared;
    }
}
