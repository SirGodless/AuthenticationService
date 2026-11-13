package net.anfall.auth.database;

import static org.assertj.core.api.Assertions.assertThat;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Daten, die die Migrationen anlegen: Rolle Admin und eingebaute Rechte. */
class MigrationSeedTest extends IntegrationTestBase {

    // ---- Sonstiges ----

    @Test
    void migrationSeedsAdminRoleAndBuiltInPermissions() {
        assertThat(jdbc.sql("SELECT all_admin_permissions FROM roles WHERE name = 'Admin' AND builtin")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT key FROM permissions WHERE key LIKE 'admin.%'").query(String.class).list())
                .containsExactlyInAnyOrder("admin.users.view", "admin.users.ban", "admin.users.reset_2fa",
                        "admin.roles.manage", "admin.permissions.manage", "admin.services.manage",
                        "admin.links.view_all", "admin.audit.view", "admin.settings.manage");
        assertThat(jdbc.sql("SELECT type, direction, allow_unlimited FROM permissions WHERE key = 'links.max_active'")
                .query().singleRow())
                .containsEntry("type", "number").containsEntry("direction", "higher")
                .containsEntry("allow_unlimited", true);
    }
}
