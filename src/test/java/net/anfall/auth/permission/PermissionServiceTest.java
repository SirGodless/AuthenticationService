package net.anfall.auth.permission;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PermissionServiceTest extends IntegrationTestBase {

    private static final String ADMIN_ROLE = "00000000-0000-0000-0000-000000000001";

    @Autowired PermissionService permissions;

    @Test
    void aNewUserHasNoPermissions() {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        assertThat(permissions.has(id, "links.create")).isFalse();
        assertThat(permissions.has(id, "admin.users.view")).isFalse();
    }

    @Test
    void directGrantAndRevoke() {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        permissions.grantToUser(id, "links.create");
        permissions.grantToUser(id, "links.create");          // doppelt ist harmlos
        assertThat(permissions.has(id, "links.create")).isTrue();
        permissions.revokeFromUser(id, "links.create");
        assertThat(permissions.has(id, "links.create")).isFalse();
    }

    @Test
    void permissionsComeFromRolesAndChangeLiveWhenTheRoleChanges() {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:r, 'Redakteure')").param("r", role).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", id).param("r", role).update();
        assertThat(permissions.has(id, "links.create")).isFalse();

        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'links.create')")
                .param("r", role).update();
        assertThat(permissions.has(id, "links.create")).isTrue();           // Live-Gruppe: gilt sofort

        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :r").param("r", role).update();
        assertThat(permissions.has(id, "links.create")).isFalse();
    }

    @Test
    void theAdminRoleContainsAllAdminPermissionsButNothingElse() {
        UUID id = createUser("admin", "admin@example.com", PASSWORD, true);
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, CAST(:r AS uuid))")
                .param("u", id).param("r", ADMIN_ROLE).update();
        assertThat(permissions.has(id, "admin.users.view")).isTrue();
        assertThat(permissions.has(id, "admin.audit.view")).isTrue();
        assertThat(permissions.has(id, "admin.something_new_later")).isTrue();     // auch später neue admin.*-Rechte
        assertThat(permissions.has(id, "links.create")).isFalse();
        assertThat(permissions.has(id, "service.vault")).isFalse();                // kein Zugang zu Diensten von sich aus
    }
}
