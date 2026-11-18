package net.anfall.auth.invite;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.InviteTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Registrierung über einen Link: Verbrauch beim Absenden, neutrale Fehler, Rechte, Prüfung des Erstellers, Rennen. */
class InviteRegistrationTest extends InviteTestBase {

    @Autowired PermissionService permissions;

    private void makeVaultGrantable() {
        jdbc.sql("INSERT INTO permissions (key, type, builtin, link_grantable) VALUES ('service.vault', 'boolean', false, true)").update();
    }

    @Test
    void registeringCreatesAnUnconfirmedAccountInvitedByTheCreatorAndSendsTheConfirmationMail() throws Exception {
        Session anna = creator("anna", 3);
        String token = createLink(anna);
        Browser.Result r = register(token, "Bob", "Bob@Example.com");
        assertThat(r.ok()).as(r.body()).isTrue();

        UUID bob = idOf("bob");
        assertThat(jdbc.sql("SELECT invited_by FROM users WHERE id = :i").param("i", bob).query(UUID.class).single()).isEqualTo(anna.id());
        assertThat(jdbc.sql("SELECT email_verified FROM user_pii WHERE user_id = :i").param("i", bob).query(Boolean.class).single()).isFalse();
        assertThat(mails()).hasSize(1);
        assertThat(mails().get(0).getAllRecipients()[0].toString()).isEqualTo("bob@example.com");
        // Ohne Bestätigung kein Zugang
        assertThat(login(browser(), "bob", NEW_PASSWORD).status()).isEqualTo(403);
        assertThat(browser().post("/api/auth/confirm-email", Map.of("token", tokenFromMail(mails().get(0), "confirm"))).ok()).isTrue();
        assertThat(login(browser(), "bob", NEW_PASSWORD).ok()).isTrue();
    }

    @Test
    void theConfirmationMailUsesTheChosenLanguage() throws Exception {
        String token = createLink(creator("anna", 3));
        Browser.Result r = browser().post("/api/invite/" + token + "/register",
                Map.of("username", "bob", "email", "bob@example.com", "password", NEW_PASSWORD, "language", "de"));
        assertThat(r.ok()).isTrue();
        assertThat(jdbc.sql("SELECT language FROM users WHERE username = 'bob'").query(String.class).single()).isEqualTo("de");
        assertThat(mails().get(0).getSubject()).doesNotContain("Confirm");
    }

    @Test
    void openingTheLinkDoesNotUseItUpButSubmittingDoes() throws Exception {
        String token = createLink(creator("anna", 3));
        for (int i = 0; i < 3; i++) {
            assertThat(browser().get("/api/invite/" + token).ok()).isTrue(); // Vorschau durch Mail-Programme
        }
        assertThat(register(token, "bob", "bob@example.com").ok()).isTrue();
        assertThat(browser().get("/api/invite/" + token).status()).isEqualTo(404);
    }

    @Test
    void formErrorsDoNotUseUpTheLink() throws Exception {
        Session anna = creator("anna", 3);
        String token = createLink(anna);
        assertThat(register(token, "anna", "x@example.com").body()).contains("username_taken");
        assertThat(register(token, "bob", "anna@example.com").body()).contains("email_taken");
        assertThat(register(browser(), token, "bob", "bob@example.com", "short").body()).contains("password_too_short");
        assertThat(register(browser(), token, "b", "bob@example.com", NEW_PASSWORD).body()).contains("username_invalid");
        assertThat(jdbc.sql("SELECT status FROM invite_links").query(String.class).single()).isEqualTo("active");
        assertThat(register(token, "bob", "bob@example.com").ok()).isTrue();
    }

    @Test
    void passwordRulesApplyJustLikeEverywhere() throws Exception {
        String token = createLink(creator("anna", 3));
        // Aus dem Username abgeleitet und Sperrliste
        assertThat(register(browser(), token, "bobby", "bob@example.com", "bobby-bobby-bobby-bobby").body()).contains("password_context");
        assertThat(register(browser(), token, "bob", "bob@example.com", "qwertyuiopasdfghjkl").body()).contains("password_blocked");
    }

    @Test
    void unknownExpiredUsedAndDeactivatedLinksAllGiveTheSameNeutralAnswer() throws Exception {
        Session anna = creator("anna", 9);
        String used = createLink(anna);
        register(used, "bob", "bob@example.com");
        Browser.Result expiredCreated = tryCreate(anna, body(null, null, null));
        String expired = tokenOf(expiredCreated);
        jdbc.sql("UPDATE invite_links SET expires_at = now() - interval '1 minute' WHERE id = :i").param("i", linkIdOf(expiredCreated)).update();
        Browser.Result deactivatedCreated = tryCreate(anna, body(null, null, null));
        String deactivated = tokenOf(deactivatedCreated);
        anna.browser().post("/api/invites/" + linkIdOf(deactivatedCreated) + "/deactivate", Map.of());

        String expectedGet = browser().get("/api/invite/unbekannt").body();
        for (String t : List.of(used, expired, deactivated)) {
            Browser.Result g = browser().get("/api/invite/" + t);
            assertThat(g.status()).isEqualTo(404);
            assertThat(g.body()).isEqualTo(expectedGet);
            Browser.Result p = register(t, "carl", "carl@example.com");
            assertThat(p.status()).isEqualTo(404);
            assertThat(p.body()).contains("invalid_link");
        }
        assertThat(register("unbekannt", "carl", "carl@example.com").body()).isEqualTo(register(used, "carl", "carl@example.com").body());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username = 'carl'").query(Long.class).single()).isZero();
    }

    @Test
    void roleAndDirectRightsAreGrantedAndRecorded() throws Exception {
        makeVaultGrantable();
        Session anna = creator("anna", 3);
        givePermission(anna.id(), "service.vault");
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Guests')").param("i", role).update();
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'service.vault')").param("r", role).update();
        setLinkGrantable("links.max_active", true);
        String token = tokenOf(tryCreate(anna, body(null, role, List.of(grant("links.max_active", 2)))));

        assertThat(register(token, "bob", "bob@example.com").ok()).isTrue();
        UUID bob = idOf("bob");
        assertThat(permissions.has(bob, "service.vault")).isTrue();                      // über die Rolle
        assertThat(permissions.value(bob, "links.max_active").number()).isEqualTo(2L);  // direkt
        String granted = jdbc.sql("SELECT granted::text FROM invite_links").query(String.class).single();
        assertThat(granted).contains("Guests", "links.max_active");
        assertThat(jdbc.sql("SELECT used_by FROM invite_links").query(UUID.class).single()).isEqualTo(bob);
    }

    @Test
    void aLinkWithoutRightsGrantsNothing() throws Exception {
        String token = createLink(creator("anna", 3));
        register(token, "bob", "bob@example.com");
        assertThat(permissions.effective(idOf("bob")).values().stream().filter(v -> v.set())).isEmpty();
    }

    // ---- Ersteller beim Einlösen neu prüfen ----

    @Test
    void ifTheCreatorWasBannedInTheMeantimeTheWholeLinkIsRefused() throws Exception {
        Session anna = creator("anna", 3);
        String token = createLink(anna);
        jdbc.sql("UPDATE users SET banned = true WHERE id = :i").param("i", anna.id()).update(); // Bann ohne den Weg über den Dienst
        assertThat(browser().get("/api/invite/" + token).status()).isEqualTo(404);
        assertThat(register(token, "bob", "bob@example.com").body()).contains("invalid_link");
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username = 'bob'").query(Long.class).single()).isZero();
    }

    @Test
    void ifTheCreatorLostARightTheWholeLinkIsRefused() throws Exception {
        makeVaultGrantable();
        Session anna = creator("anna", 3);
        givePermission(anna.id(), "service.vault");
        String token = tokenOf(tryCreate(anna, body(null, null, List.of(grant("service.vault")))));
        jdbc.sql("DELETE FROM user_permissions WHERE user_id = :u AND permission_key = 'service.vault'").param("u", anna.id()).update();
        assertThat(register(token, "bob", "bob@example.com").body()).contains("invalid_link");
        // Bekommt der Ersteller das Recht zurück, gilt der Link wieder
        givePermission(anna.id(), "service.vault");
        assertThat(register(token, "bob", "bob@example.com").ok()).isTrue();
    }

    @Test
    void ifTheRoleGainedARightTheCreatorLacksTheLinkIsRefused() throws Exception {
        makeVaultGrantable();
        Session anna = creator("anna", 3);
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Guests')").param("i", role).update();
        String token = tokenOf(tryCreate(anna, body(null, role, null)));
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'service.vault')").param("r", role).update();
        assertThat(register(token, "bob", "bob@example.com").body()).contains("invalid_link");
    }

    @Test
    void aDeletedRoleMakesTheLinkInvalid() throws Exception {
        Session anna = creator("anna", 3);
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Guests')").param("i", role).update();
        String token = tokenOf(tryCreate(anna, body(null, role, null)));
        jdbc.sql("DELETE FROM roles WHERE id = :i").param("i", role).update();
        assertThat(register(token, "bob", "bob@example.com").body()).contains("invalid_link");
    }

    @Test
    void anAdminBanDeactivatesOpenLinksButKeepsEarlierAccounts() throws Exception {
        Session admin = loggedInAdmin("joshua");
        Session anna = creator("anna", 5);
        String used = createLink(anna);
        register(used, "bob", "bob@example.com");
        Browser.Result open = tryCreate(anna, body(null, null, null));

        assertThat(admin.browser().post("/api/admin/users/" + anna.id() + "/ban", Map.of()).ok()).isTrue();

        assertThat(linkStatus(linkIdOf(open))).isEqualTo("deactivated");
        assertThat(jdbc.sql("SELECT status_reason FROM invite_links WHERE id = :i").param("i", linkIdOf(open)).query(String.class).single())
                .isEqualTo("creator_banned");
        assertThat(jdbc.sql("SELECT banned FROM users WHERE username = 'bob'").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT status FROM invite_links WHERE token_hash <> 'x' AND used_by IS NOT NULL").query(String.class).single()).isEqualTo("used");
        // Entbannen macht deaktivierte Links nicht wieder aktiv
        admin.browser().post("/api/admin/users/" + anna.id() + "/unban", Map.of());
        assertThat(linkStatus(linkIdOf(open))).isEqualTo("deactivated");
    }

    // ---- Rennen ----

    @Test
    void twoSimultaneousRegistrationsWithTheSameLinkCreateExactlyOneAccount() throws Exception {
        String token = createLink(creator("anna", 3));
        Browser b1 = browser();
        Browser b2 = browser();
        b1.open();
        b2.open();
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(
                    () -> register(b1, token, "bob", "bob@example.com", NEW_PASSWORD).status(),
                    () -> register(b2, token, "carl", "carl@example.com", NEW_PASSWORD).status());
            List<Integer> codes = pool.invokeAll(jobs).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(codes).containsExactlyInAnyOrder(200, 404);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username IN ('bob', 'carl')").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM invite_links WHERE status = 'used'").query(Long.class).single()).isEqualTo(1);
        assertThat(mails()).hasSize(1);
    }

    @Test
    void manyParallelRegistrationsStillUseTheLinkOnce() throws Exception {
        String token = createLink(creator("anna", 3));
        int n = 8;
        List<Browser> browsers = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            Browser b = browser();
            b.open();
            browsers.add(b);
        }
        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<Integer>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                jobs.add(() -> register(browsers.get(k), token, "user" + k, "user" + k + "@example.com", NEW_PASSWORD).status());
            }
            long ok = pool.invokeAll(jobs).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).filter(c -> c == 200).count();
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username LIKE 'user%'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void registrationIsWrittenToTheAuditLog() throws Exception {
        String token = createLink(creator("anna", 3));
        register(token, "bob", "bob@example.com");
        String log = jdbc.sql("SELECT string_agg(action || ' ' || details::text, ' | ') FROM audit_log WHERE action IN ('invite.created', 'account.registered')")
                .query(String.class).single();
        assertThat(log).contains("invite.created", "account.registered").doesNotContain(token).doesNotContain(NEW_PASSWORD);
    }
}
