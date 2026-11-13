package net.anfall.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Anmelden mit Username oder E-Mail und Passwort: Antworten, Sperrgründe, Unicode. */
class PasswordLoginTest extends IntegrationTestBase {

    // ---- Grundfunktion ----

    @Test
    void loginWorksWithUsernameOrEmailInAnyCase() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);

        Browser byName = browser();
        assertThat(login(byName, "joshua", PASSWORD).ok()).isTrue();
        assertThat(byName.get("/api/auth/me").bodyContains("\"username\":\"joshua\"")).isTrue();

        Browser byMail = browser();
        assertThat(login(byMail, "Joshua@Example.COM", PASSWORD).ok()).isTrue();
        assertThat(byMail.get("/api/auth/me").ok()).isTrue();

        assertThat(login(browser(), "JOSHUA", PASSWORD).ok()).isTrue();
    }

    // ---- Falsche Eingaben, gleiche Antworten ----

    @Test
    void wrongPasswordAndUnknownUserLookExactlyTheSame() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser.Result wrong = login(browser(), "joshua", "wrong password entirely");
        Browser.Result unknown = login(browser(), "niemand", "wrong password entirely");
        assertThat(wrong.status()).isEqualTo(401).isEqualTo(unknown.status());
        assertThat(wrong.body()).isEqualTo(unknown.body()).contains("invalid_credentials");
        assertThat(wrong.setCookies().stream().filter(c -> c.startsWith(SID))).isEmpty();
    }

    @Test
    void unverifiedEmailBlocksLoginButOnlyAfterTheCorrectPassword() throws Exception {
        createUser("neu", "neu@example.com", PASSWORD, false);
        // Falsches Passwort: keine Auskunft über den Zustand des Kontos
        assertThat(login(browser(), "neu", "wrong password entirely").body()).contains("invalid_credentials");
        Browser b = browser();
        Browser.Result r = login(b, "neu", PASSWORD);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("email_not_verified");
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void bannedAccountCannotLoginAndLosesItsSessions() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        assertThat(b.get("/api/auth/me").ok()).isTrue();

        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", id).update();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        Browser.Result r = login(browser(), "joshua", PASSWORD);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("account_banned");
    }

    @Test
    void passwordsAreNormalizedToNfc() throws Exception {
        // "é" als ein Zeichen und als e + Akzent: dasselbe Passwort, egal von welcher Tastatur
        createUser("joshua", "joshua@example.com", "caf\u00e9 und noch mehr text", true);
        assertThat(login(browser(), "joshua", "cafe\u0301 und noch mehr text").ok()).isTrue();
    }
}
