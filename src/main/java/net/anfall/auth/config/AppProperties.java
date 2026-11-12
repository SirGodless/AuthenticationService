package net.anfall.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Alle eigenen Einstellungen (kommen aus Umgebungsvariablen, siehe application.yml). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        /** Feste Adresse des Auth-Dienstes, ohne Slash am Ende. Wird für den Setup-Link gebraucht. */
        String publicBaseUrl,
        /** Base64 eines 32-Byte-Schlüssels für die TOTP-Geheimnisse (AES-256-GCM). */
        String totpEncryptionKey,
        /** Name, der in der Authenticator-App angezeigt wird. */
        @DefaultValue("anfall.net") String totpIssuer,
        @DefaultValue("PT1H") Duration setupTokenTtl,
        Argon2 argon2) {

    /** Mindestwerte laut OWASP: 19 MiB, 2 Durchläufe, Parallelität 1. */
    public record Argon2(
            @DefaultValue("65536") int memoryKib,
            @DefaultValue("3") int iterations,
            @DefaultValue("1") int parallelism) {
    }
}
