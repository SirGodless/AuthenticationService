package net.anfall.auth.invite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Löscht alte Zähler der IP-Sperre für Einladungslinks (die gespeicherten Adressen sollen nicht länger als nötig liegen). */
@Component
public class InviteIpBlockCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(InviteIpBlockCleanupJob.class);
    private final InviteIpBlockService blocks;

    public InviteIpBlockCleanupJob(InviteIpBlockService blocks) {
        this.blocks = blocks;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public int run() {
        int deleted = blocks.deleteOld();
        log.info("IP-Sperren für Links: {} alte Zähler gelöscht", deleted);
        return deleted;
    }
}
