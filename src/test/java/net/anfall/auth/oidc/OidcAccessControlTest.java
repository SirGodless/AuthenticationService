package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountBanService;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Wer darf an einen Dienst, und was passiert bei Entzug des Rechts, Bann, Reset und Ablauf der Sitzung. */
class OidcAccessControlTest extends OidcTestBase {

    @Autowired AccountBanService bans;

    @BeforeEach
    void setUp() {
        setUpVaultAndJoshua();
    }

    private long authorizations() {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization").query(Long.class).single();
    }

    // ---- Ohne Recht kein Code ----

    @Test
    void withoutThePermissionNoCodeIsIssuedAndTheUserSeesAnExplanationPage() throws Exception {
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        Browser b = loggedIn("anna");

        Browser.Result r = b.get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.location()).startsWith("/access-denied?service=");
        assertThat(URLDecoder.decode(r.location(), StandardCharsets.UTF_8)).endsWith("service=Vaultwarden");
        assertThat(r.location()).doesNotContain("code=").doesNotContain("client.example");
        assertThat(authorizations()).isZero();
        assertThat(jdbc.sql("SELECT subject_id FROM audit_log WHERE action = 'oidc.access_denied'")
                .query(UUID.class).single()).isEqualTo(anna);
    }

    @Test
    void thePermissionOfAnotherServiceDoesNotHelp() throws Exception {
        registration.register("audio", "Audiobookshelf", java.util.List.of("https://audio.example/cb"));
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        permissions.grantToUser(anna, "service.audio");
        Browser.Result r = loggedIn("anna").get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
        assertThat(r.location()).startsWith("/access-denied?");
    }

    @Test
    void permissionViaARoleIsEnough() throws Exception {
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:r, 'Hörer')").param("r", role).update();
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'service.vault')")
                .param("r", role).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", anna).param("r", role).update();
        Browser.Result r = loggedIn("anna").get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
        assertThat(r.location()).startsWith(OidcTestClient.REDIRECT_URI);
    }

    @Test
    void theAdminRoleAloneGivesNoAccessToServices() throws Exception {
        UUID admin = createUser("admin", "admin@example.com", PASSWORD, true);
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, '00000000-0000-0000-0000-000000000001')")
                .param("u", admin).update();
        Browser.Result r = loggedIn("admin").get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
        assertThat(r.location()).startsWith("/access-denied?");
    }

    @Test
    void aClientWithoutAServiceIsRefused_everyClientNeedsAPermission() throws Exception {
        jdbc.sql("DELETE FROM services WHERE key = 'vault'").update();
        Browser.Result r = loggedIn("joshua").get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
        assertThat(r.location()).startsWith("/access-denied?");
    }

    // ---- Refresh-Token ----

    @Test
    void refreshWorksAndRotatesTheRefreshToken() throws Exception {
        OidcTestClient.TokenResponse first = fullLogin(loggedIn("joshua"));

        OidcTestClient.TokenResponse second = vault.refresh(first.refreshToken());
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.refreshToken()).isNotBlank().isNotEqualTo(first.refreshToken());
        assertThat(vault.verify(second.accessToken()).getSubject()).isEqualTo(joshua.toString());

        // Der alte Refresh-Token gilt nicht mehr
        assertThat(vault.refresh(first.refreshToken()).status()).isEqualTo(400);
    }

    @Test
    void refreshIsRefusedAfterThePermissionWasTakenAway_andTheAuthorizationIsGone() throws Exception {
        OidcTestClient.TokenResponse tokens = fullLogin(loggedIn("joshua"));
        permissions.revokeFromUser(joshua, "service.vault");

        OidcTestClient.TokenResponse refreshed = vault.refresh(tokens.refreshToken());

        assertThat(refreshed.status()).isEqualTo(400);
        assertThat(refreshed.json().path("error").asString()).isEqualTo("invalid_grant");
        assertThat(authorizations()).isZero();
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'oidc.token_denied'")
                .query(String.class).single()).contains("permission_missing");
    }

    @Test
    void refreshIsRefusedForABannedUser_evenIfTheTokensWereNotRevoked() throws Exception {
        OidcTestClient.TokenResponse tokens = fullLogin(loggedIn("joshua"));
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", joshua).update();

        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
        assertThat(authorizations()).isZero();
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'oidc.token_denied'")
                .query(String.class).single()).contains("banned");
    }

    @Test
    void withoutStayingSignedInTheServiceSessionEndsWithTheLoginSession() throws Exception {
        Browser b = loggedIn("joshua");              // ohne "Eingeloggt bleiben"
        // Die Sitzung läuft gleich ab (statt nach 12 Stunden): Der Provider übernimmt dieses Ende
        jdbc.sql("UPDATE login_sessions SET expires_at = now() + interval '2 seconds'").update();
        OidcTestClient.TokenResponse tokens = fullLogin(b);
        OidcTestClient.TokenResponse early = vault.refresh(tokens.refreshToken());
        assertThat(early.status()).isEqualTo(200);                      // innerhalb der Sitzung geht es

        Thread.sleep(2500);
        OidcTestClient.TokenResponse late = vault.refresh(early.refreshToken());
        assertThat(late.status()).isEqualTo(400);                       // danach nicht mehr
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'oidc.token_denied'")
                .query(String.class).single()).contains("session_ended");
    }

    @Test
    void withStayingSignedInRefreshIsNotCutOffAtTheTwelveHourMark() throws Exception {
        Browser b = browser();
        assertThat(b.post("/api/auth/login", creds("joshua", PASSWORD, true)).ok()).isTrue();
        jdbc.sql("UPDATE login_sessions SET expires_at = now() + interval '2 seconds'").update();
        OidcTestClient.TokenResponse tokens = fullLogin(b);
        Thread.sleep(2500);
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(200);
    }

    // ---- Bann und Reset widerrufen ----

    @Test
    void banEndsSessionsAndRevokesRefreshTokens() throws Exception {
        UUID admin = createUser("admin", "admin@example.com", PASSWORD, true);
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, '00000000-0000-0000-0000-000000000001')")
                .param("u", admin).update();
        Browser b = loggedIn("joshua");
        OidcTestClient.TokenResponse tokens = fullLogin(b);
        assertThat(authorizations()).isEqualTo(1);

        bans.ban(admin, joshua, "203.0.113.1");

        assertThat(authorizations()).isZero();
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(login(browser(), "joshua", PASSWORD).status()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'account.banned'")
                .query(String.class).single()).contains("authorizations_revoked");
    }

    @Test
    void passwordResetRevokesRefreshTokensToo() throws Exception {
        OidcTestClient.TokenResponse tokens = fullLogin(loggedIn("joshua"));
        browser().post("/api/auth/password-reset/request", Map.of("email", "joshua@example.com"));
        String resetToken = tokenFromMail(mails().get(0), "reset");

        assertThat(browser().post("/api/auth/password-reset/confirm",
                Map.of("token", resetToken, "password", "a completely new passphrase 42")).ok()).isTrue();

        assertThat(authorizations()).isZero();
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
    }
}
