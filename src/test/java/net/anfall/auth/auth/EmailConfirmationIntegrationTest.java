package net.anfall.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.Browser;
import net.anfall.auth.IntegrationTestBase;
import net.anfall.auth.crypto.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EmailConfirmationIntegrationTest extends IntegrationTestBase {

    @Autowired EmailConfirmationService confirmation;

    private String sendConfirmation(UUID id, String email, String lang, String username) throws Exception {
        confirmation.sendFor(id, email, lang, username);
        assertThat(mails()).hasSize(1);
        return tokenFromMail(mails().get(0), "confirm");
    }

    private Browser.Result confirm(Browser b, String token) throws Exception {
        return b.post("/api/auth/confirm-email", Map.of("token", token));
    }

    @Test
    void mailGoesToTheAddressInTheAccountsLanguageWithLinkAndNoSecretsInTheDatabase() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, false, "de");
        String token = sendConfirmation(id, "anna@example.com", "de", "anna");

        MimeMessage mail = mails().get(0);
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("anna@example.com");
        assertThat(mail.getFrom()[0].toString()).isEqualTo("no-reply@anfall.net");
        assertThat(mail.getSubject()).isEqualTo("Bestätige deine E-Mail-Adresse");
        assertThat(bodyOf(mail)).contains("Hallo anna").contains("24 Stunden");

        // Nur der Hash des Tokens steht in der Datenbank
        assertThat(jdbc.sql("SELECT token_hash FROM email_tokens").query(String.class).single())
                .isEqualTo(Tokens.sha256Hex(token));
        assertThat(token).hasSize(43);
    }

    @Test
    void englishIsTheDefaultLanguage() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, false, "en");
        sendConfirmation(id, "anna@example.com", "en", "anna");
        assertThat(mails().get(0).getSubject()).isEqualTo("Confirm your email address");
        assertThat(bodyOf(mails().get(0))).contains("Hello anna").contains("24 hours");
    }

    @Test
    void confirmingEnablesLoginAndWorksOnce() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, false);
        String token = sendConfirmation(id, "anna@example.com", "en", "anna");
        Browser b = browser();

        // Vor der Bestätigung kein Login
        assertThat(b.post("/api/auth/login", Map.of("identifier", "anna", "password", PASSWORD)).status())
                .isEqualTo(403);

        // Das bloße Öffnen der Seite (Mail-Scanner, Vorschau) verbraucht den Link nicht
        assertThat(b.get("/api/auth/confirm-email/" + token).ok()).isTrue();
        assertThat(b.get("/api/auth/confirm-email/" + token).ok()).isTrue();

        assertThat(confirm(b, token).ok()).isTrue();
        assertThat(jdbc.sql("SELECT email_verified FROM user_pii WHERE user_id = :u").param("u", id)
                .query(Boolean.class).single()).isTrue();
        assertThat(b.post("/api/auth/login", Map.of("identifier", "anna", "password", PASSWORD)).ok()).isTrue();

        // zweites Mal: derselbe Fehler wie bei einem erfundenen Link
        Browser.Result again = confirm(b, token);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.body()).isEqualTo(confirm(b, Tokens.newToken()).body()).contains("invalid_link");
        assertThat(b.get("/api/auth/confirm-email/" + token).status()).isEqualTo(404);
    }

    @Test
    void expiredLinkIsRejected() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, false);
        String token = sendConfirmation(id, "anna@example.com", "en", "anna");
        assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint FROM email_tokens")
                .query(Long.class).single()).isBetween(86395L, 86405L); // 24 Stunden

        jdbc.sql("UPDATE email_tokens SET expires_at = now() - interval '1 second'").update();
        assertThat(confirm(browser(), token).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT email_verified FROM user_pii").query(Boolean.class).single()).isFalse();
    }

    // ---- Erneut senden ----

    @Test
    void resendSendsOnlyToUnverifiedExistingAddressesButAnswersAlwaysTheSame() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, false);
        createUser("bert", "bert@example.com", PASSWORD, true);
        UUID banned = createUser("carl", "carl@example.com", PASSWORD, false);
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", banned).update();

        Browser.Result unknown = browser().from("198.51.100.1")
                .post("/api/auth/resend-confirmation", Map.of("email", "niemand@example.com"));
        assertThat(mails()).isEmpty();
        Browser.Result verified = browser().from("198.51.100.2")
                .post("/api/auth/resend-confirmation", Map.of("email", "bert@example.com"));
        Browser.Result bannedResult = browser().from("198.51.100.3")
                .post("/api/auth/resend-confirmation", Map.of("email", "carl@example.com"));
        assertThat(mails()).isEmpty();
        Browser.Result existing = browser().from("198.51.100.4")
                .post("/api/auth/resend-confirmation", Map.of("email", "Anna@Example.com"));

        assertThat(mails()).hasSize(1);
        assertThat(mails().get(0).getAllRecipients()[0].toString()).isEqualTo("anna@example.com");
        assertThat(unknown.ok() && verified.ok() && bannedResult.ok() && existing.ok()).isTrue();
        assertThat(unknown.body()).isEqualTo(existing.body());
    }

    @Test
    void resendInvalidatesTheOlderLink() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, false);
        String first = sendConfirmation(id, "anna@example.com", "en", "anna");
        browser().post("/api/auth/resend-confirmation", Map.of("email", "anna@example.com"));
        assertThat(mails()).hasSize(2);
        String second = tokenFromMail(mails().get(1), "confirm");

        assertThat(confirm(browser(), first).status()).isEqualTo(404);
        assertThat(confirm(browser(), second).ok()).isTrue();
    }

    @Test
    void resendIsLimitedToThreePerHourPerEmail() throws Exception {
        createUser("anna", "anna@example.com", PASSWORD, false);
        // Jede Anfrage kommt von einer anderen IP, damit nur das E-Mail-Limit greift
        for (int i = 1; i <= 3; i++) {
            assertThat(browser().from("198.51.100." + i)
                    .post("/api/auth/resend-confirmation", Map.of("email", "anna@example.com")).ok()).isTrue();
        }
        Browser.Result fourth = browser().from("198.51.100.9")
                .post("/api/auth/resend-confirmation", Map.of("email", "anna@example.com"));
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.body()).contains("too_many_requests");
        assertThat(mails()).hasSize(3);
    }

    @Test
    void resendIsLimitedToThreePerHourPerIp_evenForUnknownAddresses() throws Exception {
        for (int i = 1; i <= 3; i++) {
            assertThat(browser().from("198.51.100.50")
                    .post("/api/auth/resend-confirmation", Map.of("email", "x" + i + "@example.com")).ok()).isTrue();
        }
        assertThat(browser().from("198.51.100.50")
                .post("/api/auth/resend-confirmation", Map.of("email", "x4@example.com")).status()).isEqualTo(429);
        // Eine andere IP ist nicht betroffen
        assertThat(browser().from("198.51.100.51")
                .post("/api/auth/resend-confirmation", Map.of("email", "x5@example.com")).ok()).isTrue();
    }

    @Test
    void ipv6AddressesOfTheSame64NetworkShareOneLimit() throws Exception {
        for (int i = 1; i <= 3; i++) {
            browser().from("2001:db8:1:2:0:0:0:" + i)
                    .post("/api/auth/resend-confirmation", Map.of("email", "y" + i + "@example.com"));
        }
        assertThat(browser().from("2001:db8:1:2:aaaa:bbbb:cccc:dddd")
                .post("/api/auth/resend-confirmation", Map.of("email", "y9@example.com")).status()).isEqualTo(429);
        assertThat(browser().from("2001:db8:1:3::1")
                .post("/api/auth/resend-confirmation", Map.of("email", "y10@example.com")).ok()).isTrue();
    }
}
