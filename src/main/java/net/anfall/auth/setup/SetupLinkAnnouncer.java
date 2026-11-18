package net.anfall.auth.setup;

import net.anfall.auth.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Beim Start ohne Admin: neuen Setup-Link erzeugen und ins Log schreiben.
 * Der Link steht bewusst im Log (CLAUDE.md Abschnitt 3 und 9). Nach dem Setup ist er wertlos.
 */
@Component
@Order(1)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!${app.cli.enabled:false}")
class SetupLinkAnnouncer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SetupLinkAnnouncer.class);
    private final SetupTokenService setup;
    private final AppProperties props;

    SetupLinkAnnouncer(SetupTokenService setup, AppProperties props) {
        this.setup = setup;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (setup.adminExists()) {
            log.info("Admin vorhanden, kein Setup-Link nötig.");
            return;
        }
        String token = setup.issueToken();
        long minutes = props.setupTokenTtl().toMinutes();
        log.warn("SETUP-LINK (einmalig, gültig {} Minuten, ersetzt ältere Links): {}/setup/{}",
                minutes, props.publicBaseUrl(), token);
    }
}
