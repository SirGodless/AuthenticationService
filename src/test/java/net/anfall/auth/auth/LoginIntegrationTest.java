package net.anfall.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.Browser;
import net.anfall.auth.IntegrationTestBase;
import net.anfall.auth.crypto.Totp;
import org.junit.jupiter.api.Test;

class LoginIntegrationTest extends IntegrationTestBase {

    private static final String SID = "__Host-sid";

    private Map<String, Object> creds(String identifier, String password, boolean remember) {
        return Map.of("identifier", identifier, "password", password, "rememberMe", remember);
    }

    private Browser.Result login(Browser b, String identifier, String password) throws Exception {
        return b.post("/api/auth/login", creds(identifier, password, false));
    }

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

    // ---- Limits ----

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheCorrectPassword() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        for (int i = 0; i < 5; i++) {
            assertThat(login(browser(), "joshua", "wrong password " + i).status()).isEqualTo(401);
        }
        Browser.Result locked = login(browser(), "joshua", PASSWORD);
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.body()).contains("too_many_attempts");

        // Nach der Pause (15 Minuten) geht es wieder
        jdbc.sql("UPDATE throttle SET locked_until = now() - interval '1 second'").update();
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue();
    }

    @Test
    void lockAppliesToUnknownNamesToo_soItDoesNotRevealWhichAccountsExist() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(browser(), "niemand", "wrong password " + i).status()).isEqualTo(401);
        }
        assertThat(login(browser(), "niemand", "wrong password 6").status()).isEqualTo(429);
    }

    @Test
    void lockIsPerAccountAndAliasesShareIt() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        createUser("anna", "anna@example.com", PASSWORD, true);
        // Fehlversuche über Username und E-Mail zählen für dasselbe Konto
        for (int i = 0; i < 3; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        for (int i = 0; i < 2; i++) {
            login(browser(), "joshua@example.com", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).status()).isEqualTo(429);
        assertThat(login(browser(), "anna", PASSWORD).ok()).isTrue(); // anderes Konto unberührt
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        for (int i = 0; i < 4; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue();
        for (int i = 0; i < 4; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue(); // 4 + 4 Fehlversuche, aber nie 5 am Stück
    }

    // ---- 2FA ----

    @Test
    void withTotpPasswordAloneIsNotEnough() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);

        Browser b = browser();
        Browser.Result step1 = login(b, "joshua", PASSWORD);
        assertThat(step1.body()).contains("totp_required");
        String pendingCookie = b.cookie(SID);
        // Wartende Sitzung ist kein Login
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(b.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", "x".repeat(20)))
                .status()).isEqualTo(401);

        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).ok()).isTrue();
        assertThat(b.get("/api/auth/me").ok()).isTrue();
        assertThat(b.cookie(SID)).isNotEqualTo(pendingCookie); // neue Sitzung (gegen Session Fixation)
        // Die alte wartende Sitzung ist weg
        Browser old = browser();
        old.setCookie(SID, pendingCookie);
        assertThat(old.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void wrongTotpCodeIsRejected() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        Browser.Result r = b.post("/api/auth/login/second-factor", Map.of("code", "000000"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("invalid_code");
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void aTotpCodeWorksOnlyOnce() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        long step = Totp.stepAt(System.currentTimeMillis());

        Browser first = browser();
        login(first, "joshua", PASSWORD);
        assertThat(first.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step))).ok())
                .isTrue();

        Browser second = browser();
        login(second, "joshua", PASSWORD);
        // derselbe Code nochmal: abgelehnt
        assertThat(second.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step)))
                .status()).isEqualTo(401);
        // der Code des nächsten Zeitfensters geht
        assertThat(second.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step + 1)))
                .ok()).isTrue();
    }

    @Test
    void fiveWrongSecondFactorsLockEvenTheRightCode() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(b.post("/api/auth/login/second-factor", Map.of("code", "00000" + i)).status()).isEqualTo(401);
        }
        Browser.Result r = b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret)));
        assertThat(r.status()).isEqualTo(429);
        // Auch eine neue Anmeldung kommt nicht an der gesperrten 2FA vorbei
        Browser again = browser();
        login(again, "joshua", PASSWORD);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).status())
                .isEqualTo(429);
    }

    @Test
    void recoveryCodeWorksOnceAndIsFormatTolerant() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        List<String> codes = addRecoveryCodes(id);

        Browser b = browser();
        login(b, "joshua", PASSWORD);
        String sloppy = codes.get(0).toLowerCase().replace("-", " ");
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", sloppy)).ok()).isTrue();
        assertThat(b.get("/api/auth/me").ok()).isTrue();

        Browser again = browser();
        login(again, "joshua", PASSWORD);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", codes.get(0))).status()).isEqualTo(401);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", codes.get(1))).ok()).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE used_at IS NOT NULL").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void secondFactorWithoutPendingLoginIsRejected() throws Exception {
        assertThat(browser().post("/api/auth/login/second-factor", Map.of("code", "123456")).status())
                .isEqualTo(401);
    }

    @Test
    void pendingLoginExpiresAfterFiveMinutes() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint FROM login_sessions")
                .query(Long.class).single()).isBetween(295L, 305L);
        jdbc.sql("UPDATE login_sessions SET expires_at = now() - interval '1 second'").update();
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).status()).isEqualTo(401);
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

    @Test
    void passwordsAreCheckedUnicodeNormalized() throws Exception {
        // "ﬁ" (Ligatur) wird per NFKC zu "fi": dasselbe Passwort von zwei Tastaturen funktioniert
        createUser("joshua", "joshua@example.com", "ﬁnal long passphrase here", true);
        assertThat(login(browser(), "joshua", "final long passphrase here").ok()).isTrue();
    }

    // ---- Protokoll ----

    @Test
    void loginsAreAuditedWithIpButWithoutSecrets() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        login(browser().from("203.0.113.7"), "joshua", "wrong password entirely");
        login(browser().from("203.0.113.7"), "joshua", PASSWORD);
        browser().from("203.0.113.8").post("/api/auth/login", creds("niemand", "wrong password entirely", false));

        var rows = jdbc.sql("SELECT action, subject_id, ip, details::text AS details FROM audit_log ORDER BY id")
                .query().listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("login.failed", "login.success", "login.failed");
        assertThat(rows.get(0)).containsEntry("ip", "203.0.113.7").containsEntry("subject_id", id);
        assertThat(rows.get(2)).containsEntry("ip", "203.0.113.8").containsEntry("subject_id", null);

        String everything = jdbc.sql("SELECT string_agg(action || coalesce(details::text,'') || coalesce(ip,''), ' ') "
                + "FROM audit_log").query(String.class).single();
        assertThat(everything).doesNotContain(PASSWORD).doesNotContain("wrong password")
                .doesNotContain("niemand"); // die eingegebene Kennung wird nicht protokolliert
    }
}
