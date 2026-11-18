package net.anfall.auth.useradmin;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** Benutzerverwaltung im Dashboard: Liste, Bann, 2FA zurücksetzen, Passwort-Reset erzwingen. */
class UserAdminApiTest extends AdminTestBase {

    @Test
    void theListSearchesUsernameAndEmail() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        createUser("anna", "anna@example.com", PASSWORD, true);
        createUser("bob", "robert@example.com", PASSWORD, true);
        String byName = admin.get("/api/admin/users?q=ann").body();
        assertThat(byName).contains("anna").doesNotContain("bob");
        assertThat(admin.get("/api/admin/users?q=robert@").body()).contains("bob").doesNotContain("\"anna\"");
        assertThat(admin.get("/api/admin/users?q=").body()).contains("anna", "bob", "joshua");
        // Platzhalter der Suche wirken nicht als Muster
        assertThat(admin.get("/api/admin/users?q=%25").body()).doesNotContain("\"anna\"");
    }

    @Test
    void theDetailShowsRolesDirectAndEffectivePermissions() throws Exception {
        Session admin = loggedInAdmin("joshua");
        String body = admin.browser().get("/api/admin/users/" + admin.id()).body();
        assertThat(body).contains("Admin", "admin.users.view", "effectivePermissions");
        assertThat(admin.browser().get("/api/admin/users/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void banAndUnbanWork() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        Browser annaBrowser = browser();
        assertThat(login(annaBrowser, "anna", PASSWORD).ok()).isTrue();

        assertThat(admin.post("/api/admin/users/" + anna + "/ban", Map.of()).ok()).isTrue();
        assertThat(annaBrowser.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(login(browser(), "anna", PASSWORD).status()).isEqualTo(403);

        assertThat(admin.post("/api/admin/users/" + anna + "/unban", Map.of()).ok()).isTrue();
        assertThat(login(browser(), "anna", PASSWORD).ok()).isTrue();
    }

    @Test
    void nobodyCanBanThemselvesOrTheLastAdmin() throws Exception {
        Session admin = loggedInAdmin("joshua");
        assertThat(admin.browser().post("/api/admin/users/" + admin.id() + "/ban", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void banNeedsTheBanRight() throws Exception {
        Browser viewer = loggedInWithPermissions("view", true, "admin.users.view").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        assertThat(viewer.post("/api/admin/users/" + anna + "/ban", Map.of()).status()).isEqualTo(403);
    }

    @Test
    void reset2faRemovesTotpCodesAndSessions() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        byte[] secret = enableTotp(anna);
        addRecoveryCodes(anna);
        Browser annaBrowser = browser();
        login(annaBrowser, "anna", PASSWORD);
        annaBrowser.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret)));
        assertThat(annaBrowser.get("/api/auth/me").ok()).isTrue();

        assertThat(admin.post("/api/admin/users/" + anna + "/reset-2fa", Map.of()).ok()).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM user_totp WHERE user_id = :u").param("u", anna).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u").param("u", anna).query(Long.class).single()).isZero();
        assertThat(annaBrowser.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(login(browser(), "anna", PASSWORD).ok()).isTrue(); // jetzt reicht das Passwort
    }

    @Test
    void forcedPasswordResetLocksThePasswordEndsSessionsAndSendsAMail() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        Browser annaBrowser = browser();
        login(annaBrowser, "anna", PASSWORD);
        GREEN_MAIL.purgeEmailFromAllMailboxes();

        assertThat(admin.post("/api/admin/users/" + anna + "/force-password-reset", Map.of()).ok()).isTrue();

        assertThat(annaBrowser.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(login(browser(), "anna", PASSWORD).status()).isEqualTo(401); // altes Passwort gilt nicht mehr
        assertThat(mails()).hasSize(1);
        String token = tokenFromMail(mails().get(0), "reset");

        assertThat(browser().post("/api/auth/password-reset/confirm",
                Map.of("token", token, "password", "a completely new passphrase 42")).ok()).isTrue();
        assertThat(login(browser(), "anna", "a completely new passphrase 42").ok()).isTrue();
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action = 'password.reset_forced'").query(String.class).list()).hasSize(1);
    }

    @Test
    void forcedPasswordResetNeedsAConfirmedEmail() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, false);
        Browser.Result r = admin.post("/api/admin/users/" + anna + "/force-password-reset", Map.of());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("email_not_verified");
        // das Passwort bleibt unverändert nutzbar bzw. unberührt
        assertThat(jdbc.sql("SELECT password_hash FROM user_credentials WHERE user_id = :u").param("u", anna)
                .query(String.class).single()).isNotEqualTo("!forced-reset");
    }

    @Test
    void forcedPasswordResetNeedsItsOwnRight() throws Exception {
        Browser banner = loggedInWithPermissions("mod", true, "admin.users.ban").browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        assertThat(banner.post("/api/admin/users/" + anna + "/force-password-reset", Map.of()).status()).isEqualTo(403);
    }
}
