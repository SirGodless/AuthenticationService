package net.anfall.auth.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Löscht alte Zähler. Eine noch aktive Sperre bleibt erhalten. */
@Component
public class ThrottleCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ThrottleCleanupJob.class);
    private final JdbcClient jdbc;

    public ThrottleCleanupJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int run() {
        int deleted = jdbc.sql("""
                        DELETE FROM throttle WHERE window_start < now() - interval '1 day'
                                               AND (locked_until IS NULL OR locked_until < now())
                        """).update();
        log.info("Zähler: {} alte gelöscht", deleted);
        return deleted;
    }
}
