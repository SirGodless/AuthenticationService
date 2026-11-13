package net.anfall.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountBanServiceTest extends IntegrationTestBase {

    @Autowired AccountBanService bans;

    private UUID admin(String name) {
        UUID id = createUser(name, name + "@example.com", PASSWORD, true);
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, '00000000-0000-0000-0000-000000000001')")
                .param("u", id).update();
        return id;
    }

    private boolean banned(UUID id) {
        return jdbc.sql("SELECT banned FROM users WHERE id = :id").param("id", id).query(Boolean.class).single();
    }

    @Test
    void banBlocksLoginAndEndsSessionsAndUnbanReverses() throws Exception {
        UUID admin = admin("admin");
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        var b = browser();
        login(b, "anna", PASSWORD);

        bans.ban(admin, anna, "203.0.113.1");

        assertThat(banned(anna)).isTrue();
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(login(browser(), "anna", PASSWORD).status()).isEqualTo(403);
        // Konto und E-Mail bleiben (reserviert)
        assertThat(jdbc.sql("SELECT count(*) FROM user_pii WHERE email = 'anna@example.com'").query(Long.class).single())
                .isEqualTo(1);

        bans.unban(admin, anna, "203.0.113.1");
        assertThat(login(browser(), "anna", PASSWORD).ok()).isTrue();
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action LIKE 'account.%' ORDER BY id")
                .query(String.class).list()).containsExactly("account.banned", "account.unbanned");
    }

    @Test
    void nobodyCanBanThemselves() {
        UUID admin = admin("admin");
        assertThatThrownBy(() -> bans.ban(admin, admin, "x")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("cannot_ban_self"));
        assertThat(banned(admin)).isFalse();
    }

    @Test
    void theLastAdminCannotBeBannedButOneOfTwoCan() {
        UUID first = admin("admin1");
        UUID helper = createUser("helper", "helper@example.com", PASSWORD, true);
        assertThatThrownBy(() -> bans.ban(helper, first, "x")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("cannot_ban_last_admin"));
        assertThat(banned(first)).isFalse();

        UUID second = admin("admin2");
        bans.ban(second, first, "x");
        assertThat(banned(first)).isTrue();
        // Jetzt ist admin2 der letzte nicht gebannte Admin
        assertThatThrownBy(() -> bans.ban(helper, second, "x")).isInstanceOf(ApiException.class);
    }

    @Test
    void aBannedAdminDoesNotCountAsAnotherAdmin() {
        UUID first = admin("admin1");
        UUID second = admin("admin2");
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", second).update();
        UUID helper = createUser("helper", "helper@example.com", PASSWORD, true);
        assertThatThrownBy(() -> bans.ban(helper, first, "x")).isInstanceOf(ApiException.class);
    }
}
