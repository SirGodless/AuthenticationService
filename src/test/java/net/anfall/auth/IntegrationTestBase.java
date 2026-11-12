package net.anfall.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.anfall.auth.crypto.RecoveryCodes;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.crypto.Totp;
import net.anfall.auth.user.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gemeinsame Grundlage der Integrationstests: echtes PostgreSQL (Testcontainers) und ein echter
 * SMTP-Server im Test (GreenMail), damit auch der Mailversand über JavaMail geprüft wird.
 * Beide starten einmal pro Testlauf und werden von allen Testklassen geteilt.
 */
@SpringBootTest(properties = {
        "app.public-base-url=https://auth.example.test",
        // 19 MiB und 2 Durchläufe sind das OWASP-Minimum, das hält die Tests schnell
        "app.argon2.memory-kib=19456",
        "app.argon2.iterations=2",
        "app.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.password-blocklist-file=src/test/resources/blocklist-test.txt",
        // Mails im Test sofort statt im Hintergrund senden
        "app.mail.async=false"
})
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "correct horse battery staple";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    protected static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        POSTGRES.start();
        GREEN_MAIL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> GREEN_MAIL.getSmtp().getPort());
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected JsonMapper json;
    @Autowired protected AccountService accounts;
    @Autowired protected SecretCipher cipher;

    @BeforeEach
    void cleanState() throws Exception {
        // permissions und roles bleiben (kommen aus der Migration)
        jdbc.sql("TRUNCATE audit_log, recovery_codes, user_totp, user_credentials, user_pii, user_roles, "
                + "setup_tokens, login_sessions, email_tokens, throttle, users CASCADE").update();
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }

    /** Legt ein Konto direkt an (ohne Registrierung, die gibt es erst in Etappe 5). */
    protected UUID createUser(String username, String email, String password, boolean emailVerified) {
        return createUser(username, email, password, emailVerified, "en");
    }

    protected UUID createUser(String username, String email, String password, boolean emailVerified, String lang) {
        return accounts.insert(new AccountService.NewAccount(username, email, password, lang), emailVerified, null);
    }

    /** Richtet TOTP für ein Konto ein und gibt das Geheimnis zurück (wie es die Authenticator-App hätte). */
    protected byte[] enableTotp(UUID userId) {
        byte[] secret = Totp.newSecret();
        jdbc.sql("INSERT INTO user_totp (user_id, secret_enc) VALUES (:u, :s)")
                .param("u", userId).param("s", cipher.encrypt(secret)).update();
        return secret;
    }

    /** Legt Wiederherstellungscodes an und gibt sie im Klartext zurück. */
    protected List<String> addRecoveryCodes(UUID userId) {
        List<String> codes = RecoveryCodes.generate();
        for (String code : codes) {
            jdbc.sql("INSERT INTO recovery_codes (id, user_id, code_hash) VALUES (:id, :u, :h)")
                    .param("id", UUID.randomUUID()).param("u", userId).param("h", RecoveryCodes.hash(code)).update();
        }
        return codes;
    }

    protected static String totpNow(byte[] secret) {
        return Totp.codeForStep(secret, Totp.stepAt(System.currentTimeMillis()));
    }

    protected Browser browser() {
        return new Browser(mvc, json);
    }

    // ---- Mails ----

    protected List<MimeMessage> mails() {
        return List.of(GREEN_MAIL.getReceivedMessages());
    }

    protected static String bodyOf(MimeMessage m) throws Exception {
        return String.valueOf(m.getContent());
    }

    /** Holt den Token aus dem Link in der Mail: https://auth.example.test/<pfad>/<token> */
    protected static String tokenFromMail(MimeMessage m, String path) throws Exception {
        Matcher matcher = Pattern.compile("https://auth\\.example\\.test/" + path + "/([A-Za-z0-9_-]+)")
                .matcher(bodyOf(m));
        assertThat(matcher.find()).as("Link /" + path + "/ in der Mail").isTrue();
        return matcher.group(1);
    }
}
