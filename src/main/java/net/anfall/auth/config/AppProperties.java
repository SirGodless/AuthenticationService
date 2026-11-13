package net.anfall.auth.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Alle eigenen Einstellungen (kommen aus Umgebungsvariablen, siehe application.yml). */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        /** Feste Adresse des Auth-Dienstes, ohne Slash am Ende. Wird für Links in Mails und das Setup gebraucht. */
        String publicBaseUrl,
        /** Base64 eines 32-Byte-Schlüssels für die TOTP-Geheimnisse (AES-256-GCM). */
        String totpEncryptionKey,
        /** Name, der in der Authenticator-App angezeigt wird. */
        @DefaultValue("anfall.net") String totpIssuer,
        @DefaultValue("PT1H") Duration setupTokenTtl,
        Argon2 argon2,
        /** Datei mit bekannten Passwörtern, eines pro Zeile (wird beim Docker-Build geladen). */
        String passwordBlocklistFile,
        /** Absenderadresse der Mails. */
        @DefaultValue("no-reply@anfall.net") String mailFrom,
        @DefaultValue Session session,
        @DefaultValue Tokens tokens,
        @DefaultValue Limits limits,
        /**
         * Adressbereiche, deren X-Forwarded-For wir glauben (Caddy im Docker-Netz). Von anderen Adressen
         * wird der Header ignoriert, sonst ließen sich IP-Sperren mit einem selbst gesetzten Header umgehen.
         */
        @DefaultValue({"127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "::1/128", "fc00::/7"})
        List<String> trustedProxies) {

    /** Mindestwerte laut OWASP: 19 MiB, 2 Durchläufe, Parallelität 1. */
    public record Argon2(
            @DefaultValue("65536") int memoryKib,
            @DefaultValue("3") int iterations,
            @DefaultValue("1") int parallelism) {
    }

    /** Laufzeit der Anmeldung im Browser (Cookie). */
    public record Session(
            /** Ohne "Eingeloggt bleiben". */
            @DefaultValue("PT12H") Duration ttl,
            /** Mit "Eingeloggt bleiben". */
            @DefaultValue("P30D") Duration rememberTtl,
            /** So lange darf zwischen Passwort und 2FA-Code vergehen. */
            @DefaultValue("PT5M") Duration pendingTtl) {
    }

    public record Tokens(
            @DefaultValue("PT24H") Duration confirmEmailTtl,
            @DefaultValue("PT1H") Duration passwordResetTtl) {
    }

    public record Limits(
            /** Falsche Passwörter oder 2FA-Codes je Konto ... */
            @DefaultValue("5") int loginMaxFailures,
            /** ... innerhalb dieser Zeit ... */
            @DefaultValue("PT15M") Duration loginWindow,
            /** ... führen zu dieser Pause. */
            @DefaultValue("PT15M") Duration loginLock,
            /** Mail-Anfragen (Bestätigung erneut senden, Passwort-Reset) je E-Mail und je IP ... */
            @DefaultValue("3") int mailRequestsMax,
            /** ... innerhalb dieser Zeit. */
            @DefaultValue("PT1H") Duration mailRequestsWindow) {
    }
}
