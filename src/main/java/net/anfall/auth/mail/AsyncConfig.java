package net.anfall.auth.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableAsync
@ConditionalOnProperty(name = "app.mail.async", havingValue = "true", matchIfMissing = true)
class AsyncConfig {
}
