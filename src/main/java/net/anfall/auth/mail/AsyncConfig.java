package net.anfall.auth.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Mails im Hintergrund senden. Mit app.mail.async=false (Tests) läuft der Versand sofort. */
@Configuration
@EnableAsync
@ConditionalOnProperty(name = "app.mail.async", havingValue = "true", matchIfMissing = true)
class AsyncConfig {
}
