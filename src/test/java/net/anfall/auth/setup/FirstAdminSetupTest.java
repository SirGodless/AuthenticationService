package net.anfall.auth.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.anfall.auth.twofactor.RecoveryCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Der erste Admin: Ablauf, Pflicht-2FA, Eingabeprüfung, gleichzeitige Anfragen. */
class FirstAdminSetupTest extends SetupTestBase {

    // ---- Der Ablauf ----

    @Test
    void fullFlowCreatesAdminWithRoleTotpAndRecoveryCodes() throws Exception {
        List<String> codes = createAdminViaSetup();

        assertThat(codes).hasSize(10);
        assertThat(setup.adminExists()).isTrue();

        // Konto: Username klein, E-Mail normalisiert und bestätigt, Sprache gespeichert
        assertThat(jdbc.sql("SELECT username, language FROM users").query().singleRow())
                .containsEntry("username", "joshua").containsEntry("language", "de");
        assertThat(jdbc.sql("SELECT email, email_verified FROM user_pii").query().singleRow())
                .containsEntry("email", "joshua@example.com").containsEntry("email_verified", true);

        // Passwort nur als Argon2id-Hash
        String hash = jdbc.sql("SELECT password_hash FROM user_credentials").query(String.class).single();
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);

        // Eingebaute Rolle Admin ist zugewiesen
        assertThat(jdbc.sql("""
                SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                """).query(String.class).list()).containsExactly("Admin");

        // TOTP-Geheimnis ist verschlüsselt gespeichert und entschlüsselbar
        byte[] enc = jdbc.sql("SELECT secret_enc FROM user_totp").query(byte[].class).single();
        assertThat(cipher.decrypt(enc)).hasSize(20);

        // Wiederherstellungscodes: nur Hashes in der Datenbank, keine Klartextcodes
        List<String> stored = jdbc.sql("SELECT code_hash FROM recovery_codes").query(String.class).list();
        assertThat(stored).hasSize(10).doesNotContainAnyElementsOf(codes)
                .containsExactlyInAnyOrderElementsOf(codes.stream().map(RecoveryCodes::hash).toList());

        // Link ist verbraucht, das Zwischengeheimnis entfernt
        assertThat(jdbc.sql("SELECT used_at IS NOT NULL AS used, pending_totp_enc IS NULL AS cleared FROM setup_tokens")
                .query().singleRow()).containsEntry("used", true).containsEntry("cleared", true);

        // Protokoll ohne Geheimnisse
        assertThat(jdbc.sql("SELECT action FROM audit_log").query(String.class).list())
                .containsExactly("setup.admin_created");
        assertThat(jdbc.sql("SELECT details::text FROM audit_log").query(String.class).single())
                .doesNotContain(PASSWORD);
    }

    // ---- 2FA ist Pflicht ----

    @Test
    void withoutValidTotpCodeNoAdminIsCreatedAndLinkSurvives() throws Exception {
        String token = setup.issueToken();
        String secret = prepare(token);

        // ohne prepare-Schritt gäbe es kein Geheimnis: erst nach prepare prüfen wir den falschen Code
        postComplete(token, "joshua", "000000").andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Long.class).single()).isZero();

        // Link wurde nicht verbraucht, der richtige Code funktioniert danach noch
        postComplete(token, "joshua", codeFor(secret)).andExpect(status().isOk());
        assertThat(setup.adminExists()).isTrue();
    }

    @Test
    void completeWithoutPrepareIsRejected() throws Exception {
        String token = setup.issueToken();
        MvcResult r = postComplete(token, "joshua", "123456").andExpect(status().isBadRequest()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("totp_not_prepared");
    }

    @Test
    void prepareReturnsTheSameSecretWhileLinkIsValid() throws Exception {
        String token = setup.issueToken();
        assertThat(prepare(token)).isEqualTo(prepare(token));
    }

    // ---- Eingabeprüfung (Link bleibt dabei erhalten) ----

    @Test
    void badInputIsRejectedWithCodesAndDoesNotConsumeTheLink() throws Exception {
        String token = setup.issueToken();
        String code = prepareAndCode(token);

        assertError(token, "ab", "joshua@example.com", PASSWORD, "en", code, 400, "username_invalid");
        assertError(token, "Jösh", "joshua@example.com", PASSWORD, "en", code, 400, "username_invalid");
        assertError(token, "joshua", "kaputt", PASSWORD, "en", code, 400, "email_invalid");
        assertError(token, "joshua", "joshua@example.com", "kurz", "en", code, 400, "password_too_short");
        assertError(token, "joshua", "joshua@example.com", "a".repeat(129), "en", code, 400, "password_too_long");
        assertError(token, "joshua", "joshua@example.com", PASSWORD, "fr", code, 400, "language_invalid");

        // Nach all den Fehlern geht der Link noch
        postComplete(token, "joshua", code).andExpect(status().isOk());
    }

    @Test
    void passwordOnTheBlocklistIsRejectedAtSetup() throws Exception {
        String token = setup.issueToken();
        String code = prepareAndCode(token);
        assertError(token, "joshua", "joshua@example.com", "password1234567890", "en", code, 400, "password_blocked");
        postComplete(token, "joshua", code).andExpect(status().isOk());
    }

    @Test
    void longPasswordsUpTo128AreAccepted() throws Exception {
        String token = setup.issueToken();
        String code = prepareAndCode(token);
        mvc.perform(post("/api/setup/" + token + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content(body("joshua", "joshua@example.com", "x".repeat(128), "en", code)))
                .andExpect(status().isOk());
    }

    // ---- Gleichzeitigkeit ----

    @Test
    void parallelRequestsCreateExactlyOneAdmin() throws Exception {
        String token = setup.issueToken();
        String code = prepareAndCode(token);

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int nr = i;
            Callable<Integer> task = () -> {
                start.await();
                return mvc.perform(post("/api/setup/" + token + "/complete")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("admin" + nr, "admin" + nr + "@example.com", PASSWORD, "en", code)))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 404)).hasSize(n - 1);
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_roles").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes").query(Long.class).single()).isEqualTo(10);
    }

    @Test
    void secretResponsesAreNotCached() throws Exception {
        String token = setup.issueToken();
        MvcResult r = mvc.perform(post("/api/setup/" + token + "/prepare")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }
}
