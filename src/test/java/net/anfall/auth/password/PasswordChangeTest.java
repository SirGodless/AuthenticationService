package net.anfall.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Passwort ändern (angemeldet). */
class PasswordChangeTest extends IntegrationTestBase {

    // ---- Passwort ändern ----

    @Test
    void changePasswordNeedsTheCurrentOneAndEndsOtherSessions() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser a = browser();
        Browser other = browser();
        login(a, "joshua", PASSWORD);
        login(other, "joshua", PASSWORD);
        String newPassword = "another long passphrase 2026";

        // falsches aktuelles Passwort
        assertThat(a.post("/api/auth/password", Map.of("currentPassword", "nope nope nope nope", "newPassword",
                newPassword)).status()).isEqualTo(401);
        // zu kurz und auf der Sperrliste
        assertThat(a.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", "kurz"))
                .body()).contains("password_too_short");
        assertThat(a.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword",
                "password1234567890")).body()).contains("password_blocked");

        assertThat(a.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", newPassword))
                .ok()).isTrue();
        assertThat(a.get("/api/auth/me").ok()).isTrue();               // aktuelle Sitzung bleibt
        assertThat(other.get("/api/auth/me").status()).isEqualTo(401); // andere enden

        assertThat(login(browser(), "joshua", PASSWORD).status()).isEqualTo(401);
        assertThat(login(browser(), "joshua", newPassword).ok()).isTrue();
    }
}
