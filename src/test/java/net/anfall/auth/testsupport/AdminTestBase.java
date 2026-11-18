package net.anfall.auth.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;

/** Hilfen für Tests der Admin-Schnittstellen: ein eingeloggter Admin-Browser (mit 2FA) oder ein User mit einzelnen Rechten. */
public abstract class AdminTestBase extends IntegrationTestBase {

    /** Ein eingeloggter User mit seinem Browser. */
    public record Session(UUID id, Browser browser, byte[] totpSecret) {
    }

    protected UUID adminRoleId() {
        return jdbc.sql("SELECT id FROM roles WHERE name = 'Admin'").query(UUID.class).single();
    }

    /** User mit der eingebauten Rolle Admin, 2FA eingerichtet und eingeloggt. */
    protected Session loggedInAdmin(String username) throws Exception {
        UUID id = createUser(username, username + "@example.com", PASSWORD, true);
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", id).param("r", adminRoleId()).update();
        return loginWithTotp(id, username);
    }

    /** User, der nur die angegebenen Rechte direkt bekommt (z. B. "admin.users.view"). */
    protected Session loggedInWithPermissions(String username, boolean withTotp, String... permissionKeys) throws Exception {
        UUID id = createUser(username, username + "@example.com", PASSWORD, true);
        for (String key : permissionKeys) {
            jdbc.sql("INSERT INTO user_permissions (user_id, permission_key) VALUES (:u, :k)").param("u", id).param("k", key).update();
        }
        return withTotp ? loginWithTotp(id, username) : loginPlain(id, username);
    }

    private Session loginWithTotp(UUID id, String username) throws Exception {
        byte[] secret = enableTotp(id);
        Browser b = browser();
        assertThat(login(b, username, PASSWORD).body()).contains("totp_required");
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).ok()).isTrue();
        return new Session(id, b, secret);
    }

    private Session loginPlain(UUID id, String username) throws Exception {
        Browser b = browser();
        assertThat(login(b, username, PASSWORD).ok()).isTrue();
        return new Session(id, b, null);
    }
}
