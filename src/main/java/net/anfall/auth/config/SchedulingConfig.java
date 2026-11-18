package net.anfall.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Schaltet die zeitgesteuerten Aufräum-Jobs ein (je einer pro Fachthema, Name endet auf Job). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
