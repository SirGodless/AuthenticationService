package net.anfall.auth.auth;

import net.anfall.auth.security.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Aufräumen im Hintergrund. Läuft stündlich. */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);
    private final JdbcClient jdbc;
    private final SessionService sessions;

    public MaintenanceJobs(JdbcClient jdbc, SessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void runAll() {
        int ips = clearOldIpAddresses();
        int expired = sessions.deleteExpired();
        int throttles = deleteOldThrottleRows();
        int tokens = deleteOldEmailTokens();
        log.info("Aufräumen: {} IP-Adressen geleert, {} Sitzungen, {} Zähler, {} Mail-Tokens gelöscht",
                ips, expired, throttles, tokens);
    }

    /** IP-Adressen im Protokoll werden nach 90 Tagen geleert. Der Eintrag selbst bleibt. */
    public int clearOldIpAddresses() {
        return jdbc.sql("UPDATE audit_log SET ip = NULL WHERE ip IS NOT NULL AND at < now() - interval '90 days'")
                .update();
    }

    public int deleteOldThrottleRows() {
        return jdbc.sql("""
                        DELETE FROM throttle WHERE window_start < now() - interval '1 day'
                                               AND (locked_until IS NULL OR locked_until < now())
                        """).update();
    }

    /** Abgelaufene oder benutzte Mail-Tokens werden nach 7 Tagen gelöscht. */
    public int deleteOldEmailTokens() {
        return jdbc.sql("""
                        DELETE FROM email_tokens WHERE COALESCE(used_at, expires_at) < now() - interval '7 days'
                        """).update();
    }
}
