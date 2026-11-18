package net.anfall.auth.login.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Löscht abgelaufene Anmeldesitzungen. */
@Component
public class ExpiredSessionsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredSessionsJob.class);
    private final SessionService sessions;

    public ExpiredSessionsJob(SessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public int run() {
        int deleted = sessions.deleteExpired();
        log.info("Sitzungen: {} abgelaufene gelöscht", deleted);
        return deleted;
    }
}
