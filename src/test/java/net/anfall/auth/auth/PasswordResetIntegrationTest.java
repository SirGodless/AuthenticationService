package net.anfall.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.anfall.auth.Browser;
import net.anfall.auth.IntegrationTestBase;
import net.anfall.auth.crypto.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class PasswordResetIntegrationTest extends IntegrationTestBase {

    @Autowired JavaMailSenderImpl sender;

    private static final String NEW_PASSWORD = "a completely new passphrase 42";

    private Browser.Result request(String email) throws Exception {
        return browser().post("/api/auth/password-reset/request", Map.of("email", email));
    }

    private Browser.Result requestFrom(String ip, String email) throws Exception {
        return browser().from(ip).post("/api/auth/password-reset/request", Map.of("email", email));
    }

    private String requestAndGetToken(String email) throws Exception {
        int before = mails().size();
        assertThat(request(email).ok()).isTrue();
        assertThat(mails()).hasSize(before + 1);
        return tokenFromMail(mails().get(before), "reset");
    }

    private Browser.Result confirm(Browser b, String token, String password) throws Exception {
        return b.post("/api/auth/password-reset/confirm", Map.of("token", token, "password", password));
    }

    // ---- Anfrage ----

    @Test
    void resetMailGoesOnlyToVerifiedUnbannedAccountsButTheAnswerIsAlwaysTheSame() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true, "de");
        createUser("neu", "neu@example.com", PASSWORD, false);
        UUID banned = createUser("carl", "carl@example.com", PASSWORD, true);
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", banned).update();

        Browser.Result unknown = requestFrom("198.51.100.1", "niemand@example.com");
        Browser.Result unverified = requestFrom("198.51.100.2", "neu@example.com");
        Browser.Result bannedResult = requestFrom("198.51.100.3", "carl@example.com");
        assertThat(mails()).isEmpty();
        Browser.Result existing = requestFrom("198.51.100.4", "ANNA@example.com");

        assertThat(mails()).hasSize(1);
        assertThat(mails().get(0).getAllRecipients()[0].toString()).isEqualTo("anna@example.com");
        assertThat(mails().get(0).getSubject()).isEqualTo("Passwort zurücksetzen"); // Sprache des Accounts
        assertThat(bodyOf(mails().get(0))).contains("1 Stunde");
        assertThat(List.of(unknown, unverified, bannedResult, existing)).allMatch(Browser.Result::ok);
        assertThat(unknown.body()).isEqualTo(existing.body()).isEqualTo(unverified.body());
    }

    @Test
    void invalidAddressLooksTheSameAsAnUnknownOne() throws Exception {
        Browser.Result garbage = requestFrom("198.51.100.1", "kein-mail-format");
        Browser.Result unknown = requestFrom("198.51.100.2", "niemand@example.com");
        assertThat(garbage.status()).isEqualTo(unknown.status());
        assertThat(garbage.body()).isEqualTo(unknown.body());
    }

    @Test
    void mailServerOutageDoesNotChangeTheAnswer() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        // Ausfall simulieren: Der Versand zeigt auf einen Port, an dem niemand lauscht
        int workingPort = sender.getPort();
        sender.setPort(1);
        try {
            assertThat(request("anna@example.com").ok()).isTrue();
            assertThat(mails()).isEmpty();
        } finally {
            sender.setPort(workingPort);
        }
    }

    @Test
    void limitIsThreePerHourPerEmailAndPerIpSeparately() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        for (int i = 1; i <= 3; i++) {
            assertThat(requestFrom("198.51.100." + i, "anna@example.com").ok()).isTrue();
        }
        // vierte Anfrage für dieselbe Adresse, von neuer IP: gesperrt
        Browser.Result byEmail = requestFrom("198.51.100.9", "anna@example.com");
        assertThat(byEmail.status()).isEqualTo(429);
        assertThat(byEmail.body()).contains("too_many_requests");
        // Auch für unbekannte Adressen gilt das Limit, damit es nichts verrät
        for (int i = 1; i <= 3; i++) {
            assertThat(requestFrom("198.51.100.77", "u" + i + "@example.com").ok()).isTrue();
        }
        assertThat(requestFrom("198.51.100.77", "u4@example.com").status()).isEqualTo(429);
        assertThat(mails()).hasSize(3);
    }

    @Test
    void newRequestInvalidatesTheOlderLink() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        String first = requestAndGetToken("anna@example.com");
        String second = requestAndGetToken("anna@example.com");
        assertThat(confirm(browser(), first, NEW_PASSWORD).status()).isEqualTo(404);
        assertThat(confirm(browser(), second, NEW_PASSWORD).ok()).isTrue();
    }

    // ---- Durchführung ----

    @Test
    void resetSetsNewPasswordEndsAllSessionsAndKeepsTotp() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        Browser a = browser();
        Browser b = browser();
        a.post("/api/auth/login", Map.of("identifier", "anna", "password", PASSWORD));
        b.post("/api/auth/login", Map.of("identifier", "anna", "password", PASSWORD));
        assertThat(a.get("/api/auth/me").ok() && b.get("/api/auth/me").ok()).isTrue();
        enableTotp(id); // erst jetzt, damit die Sitzungen oben ohne 2FA entstehen
        addRecoveryCodes(id);

        String token = requestAndGetToken("anna@example.com");
        assertThat(browser().get("/api/auth/password-reset/" + token).ok()).isTrue(); // Prüfen verbraucht nichts
        assertThat(confirm(browser(), token, NEW_PASSWORD).ok()).isTrue();

        assertThat(a.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM login_sessions").query(Long.class).single()).isZero();
        // 2FA bleibt, auch die Wiederherstellungscodes
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes").query(Long.class).single()).isEqualTo(10);

        // altes Passwort geht nicht mehr, neues schon (dann mit 2FA-Schritt)
        assertThat(browser().post("/api/auth/login", Map.of("identifier", "anna", "password", PASSWORD))
                .status()).isEqualTo(401);
        assertThat(browser().post("/api/auth/login", Map.of("identifier", "anna", "password", NEW_PASSWORD))
                .body()).contains("totp_required");
    }

    @Test
    void linkWorksOnceAndExpiresAfterOneHour() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        String token = requestAndGetToken("anna@example.com");
        assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint FROM email_tokens")
                .query(Long.class).single()).isBetween(3595L, 3605L);

        assertThat(confirm(browser(), token, NEW_PASSWORD).ok()).isTrue();
        Browser.Result again = confirm(browser(), token, "yet another passphrase 77");
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.body()).isEqualTo(confirm(browser(), Tokens.newToken(), NEW_PASSWORD).body());

        String second = requestAndGetToken("anna@example.com");
        jdbc.sql("UPDATE email_tokens SET expires_at = now() - interval '1 second' WHERE used_at IS NULL").update();
        assertThat(confirm(browser(), second, NEW_PASSWORD).status()).isEqualTo(404);
        assertThat(browser().get("/api/auth/password-reset/" + second).status()).isEqualTo(404);
    }

    @Test
    void weakPasswordsAreRejectedWithoutUsingUpTheLink() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        String token = requestAndGetToken("anna@example.com");

        assertThat(confirm(browser(), token, "kurz").body()).contains("password_too_short");
        assertThat(confirm(browser(), token, "a".repeat(129)).body()).contains("password_too_long");
        assertThat(confirm(browser(), token, "qwertyuiopasdfghjkl").body()).contains("password_blocked");
        // ein ungültiger Link zeigt nie Passwortfehler, sondern nur die neutrale Meldung
        assertThat(confirm(browser(), Tokens.newToken(), "kurz").body()).contains("invalid_link");

        assertThat(confirm(browser(), token, NEW_PASSWORD).ok()).isTrue();
    }

    @Test
    void parallelConfirmationsOfTheSameLinkSucceedExactlyOnce() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, true);
        String token = requestAndGetToken("anna@example.com");

        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Browser b = browser().open();
            String password = "parallel passphrase number " + i;
            results.add(pool.submit(() -> {
                start.await();
                return confirm(b, token, password).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 404)).hasSize(n - 1);
    }

    @Test
    void resetIsAudited() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        String token = requestAndGetToken("anna@example.com");
        confirm(browser().from("203.0.113.9"), token, NEW_PASSWORD);
        var rows = jdbc.sql("SELECT action, subject_id FROM audit_log ORDER BY id").query().listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("password_reset.requested", "password.reset");
        assertThat(rows.get(1)).containsEntry("subject_id", id);
        String everything = jdbc.sql("SELECT string_agg(details::text || coalesce(ip, ''), ' ') FROM audit_log")
                .query(String.class).single();
        assertThat(everything).doesNotContain(token).doesNotContain(NEW_PASSWORD);
    }
}
