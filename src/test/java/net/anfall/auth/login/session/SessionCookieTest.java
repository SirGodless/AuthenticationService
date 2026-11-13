package net.anfall.auth.login.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Sitzungs-Cookie, CSRF-Schutz, Laufzeiten und Abmelden. */
class SessionCookieTest extends IntegrationTestBase {

    @Test
    void sessionCookieIsHostOnlyHttpOnlySecureSameSiteLax() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser b = browser();
        String line = login(b, "joshua", PASSWORD).setCookies().stream().filter(c -> c.startsWith(SID + "="))
                .findFirst().orElseThrow();
        assertThat(line).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/").doesNotContain("Domain");
        // Ohne "Eingeloggt bleiben" ein Sitzungs-Cookie (verschwindet beim Schließen des Browsers)
        assertThat(line).doesNotContain("Max-Age");
    }

    @Test
    void csrfCookieIsReadableByTheUiButSecure() throws Exception {
        Browser b = browser();
        String line = b.get("/api/auth/me").setCookies().stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        // SameSite=Lax setzt der Servlet-Container, MockMvc zeigt es nicht an: das prüft der Browser-Test (tests/e2e)
        assertThat(line).contains("Secure").doesNotContain("HttpOnly");
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        assertThat(browser().postWithoutCsrf("/api/auth/login", creds("joshua", PASSWORD, false)).status())
                .isEqualTo(403);
    }

    // ---- Laufzeit der Sitzung ----

    @Test
    void sessionLastsTwelveHoursOrThirtyDaysWithRememberMe() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);

        browser().post("/api/auth/login", creds("joshua", PASSWORD, false));
        assertThat(sessionSeconds()).isBetween(12L * 3600 - 5, 12L * 3600 + 5);

        jdbc.sql("DELETE FROM login_sessions").update();
        Browser b = browser();
        Browser.Result r = b.post("/api/auth/login", creds("joshua", PASSWORD, true));
        assertThat(sessionSeconds()).isBetween(30L * 86400 - 5, 30L * 86400 + 5);
        // Mit "Eingeloggt bleiben" überlebt das Cookie den Browser: Max-Age gesetzt
        assertThat(r.setCookies().stream().filter(c -> c.startsWith(SID + "=")).findFirst().orElseThrow())
                .contains("Max-Age=" + 30 * 86400);
    }

    private long sessionSeconds() {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint FROM login_sessions")
                .query(Long.class).single();
    }

    @Test
    void expiredSessionIsNotAccepted() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        jdbc.sql("UPDATE login_sessions SET expires_at = now() - interval '1 second'").update();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void sessionIdIsNotStoredInTheDatabase() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        assertThat(jdbc.sql("SELECT id_hash FROM login_sessions").query(String.class).single())
                .isNotEqualTo(b.cookie(SID)).hasSize(64);
    }

    // ---- Abmelden ----

    @Test
    void logoutEndsTheSessionOnTheServer() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        String stolen = b.cookie(SID);

        Browser.Result r = b.post("/api/auth/logout", Map.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.setCookies().stream().filter(c -> c.startsWith(SID)).findFirst().orElseThrow())
                .contains("Max-Age=0");
        // Auch ein vorher abgegriffenes Cookie ist jetzt wertlos
        Browser thief = browser();
        thief.setCookie(SID, stolen);
        assertThat(thief.get("/api/auth/me").status()).isEqualTo(401);
    }
}
