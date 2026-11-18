package net.anfall.auth.role;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Rollen als Live-Gruppen und ihre Verwaltung über die Admin-API. */
class RoleAdminApiTest extends AdminTestBase {

    @Autowired PermissionService permissions;

    private UUID createRole(Browser admin, String name) throws Exception {
        Browser.Result r = admin.post("/api/admin/roles", Map.of("name", name));
        assertThat(r.ok()).as(r.body()).isTrue();
        return UUID.fromString(json.readTree(r.body()).get("id").asString());
    }

    @Test
    void rolesAreLiveGroupsAChangeAppliesToMembersImmediately() throws Exception {
        Session admin = loggedInAdmin("joshua");
        UUID member = createUser("anna", "anna@example.com", PASSWORD, true);
        UUID role = createRole(admin.browser(), "Helpers");
        assertThat(admin.browser().put("/api/admin/users/" + member + "/roles/" + role, Map.of()).ok()).isTrue();
        assertThat(permissions.has(member, "links.create")).isFalse();

        assertThat(admin.browser().put("/api/admin/roles/" + role + "/permissions/links.create", Map.of()).ok()).isTrue();
        assertThat(permissions.has(member, "links.create")).isTrue();

        assertThat(admin.browser().delete("/api/admin/roles/" + role + "/permissions/links.create").ok()).isTrue();
        assertThat(permissions.has(member, "links.create")).isFalse();
    }

    @Test
    void numberValuesInRolesAreCombinedFromAllSources() throws Exception {
        Session admin = loggedInAdmin("joshua");
        UUID member = createUser("anna", "anna@example.com", PASSWORD, true);
        UUID small = createRole(admin.browser(), "Small");
        UUID big = createRole(admin.browser(), "Big");
        admin.browser().put("/api/admin/roles/" + small + "/permissions/links.max_active", Map.of("number", 2));
        admin.browser().put("/api/admin/roles/" + big + "/permissions/links.max_active", Map.of("number", 5));
        admin.browser().put("/api/admin/users/" + member + "/roles/" + small, Map.of());
        assertThat(permissions.value(member, "links.max_active")).isEqualTo(PermissionValue.ofNumber(2));
        admin.browser().put("/api/admin/users/" + member + "/roles/" + big, Map.of());
        assertThat(permissions.value(member, "links.max_active")).isEqualTo(PermissionValue.ofNumber(5));
        // Beim Entfernen der größeren Rolle wird neu berechnet, nicht abgezogen
        admin.browser().delete("/api/admin/users/" + member + "/roles/" + big);
        assertThat(permissions.value(member, "links.max_active")).isEqualTo(PermissionValue.ofNumber(2));
    }

    @Test
    void wrongValueTypesAreRejected() throws Exception {
        Session admin = loggedInAdmin("joshua");
        UUID role = createRole(admin.browser(), "Helpers");
        // Zahl bei booleschem Recht, kein Wert bei Zahlenrecht
        assertThat(admin.browser().put("/api/admin/roles/" + role + "/permissions/links.create", Map.of("number", 3)).status())
                .isEqualTo(400);
        assertThat(admin.browser().put("/api/admin/roles/" + role + "/permissions/links.max_active", Map.of()).status())
                .isEqualTo(400);
        assertThat(admin.browser().put("/api/admin/roles/" + role + "/permissions/no.such_right", Map.of()).status())
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void theAdminRoleCannotBeRenamedDeletedOrGivenAdminEntries() throws Exception {
        Session admin = loggedInAdmin("joshua");
        String adminRole = adminRoleId().toString();
        assertThat(admin.browser().patch("/api/admin/roles/" + adminRole, Map.of("name", "Chef")).status()).isEqualTo(400);
        assertThat(admin.browser().delete("/api/admin/roles/" + adminRole).status()).isEqualTo(400);
        Browser.Result r = admin.browser().put("/api/admin/roles/" + adminRole + "/permissions/admin.users.ban", Map.of());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("role_has_all_admin_permissions");
    }

    @Test
    void roleNamesAreUniqueAndValidated() throws Exception {
        Session admin = loggedInAdmin("joshua");
        createRole(admin.browser(), "Helpers");
        assertThat(admin.browser().post("/api/admin/roles", Map.of("name", "Helpers")).status()).isEqualTo(409);
        assertThat(admin.browser().post("/api/admin/roles", Map.of("name", "x")).status()).isEqualTo(400);
    }

    @Test
    void theLastAdminCannotLoseTheAdminRole() throws Exception {
        Session admin = loggedInAdmin("joshua");
        Browser.Result r = admin.browser().delete("/api/admin/users/" + admin.id() + "/roles/" + adminRoleId());
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("cannot_remove_last_admin");
        // Mit einem zweiten Admin darf die Rolle entzogen werden
        UUID second = createUser("anna", "anna@example.com", PASSWORD, true);
        assertThat(admin.browser().put("/api/admin/users/" + second + "/roles/" + adminRoleId(), Map.of()).ok()).isTrue();
        assertThat(admin.browser().delete("/api/admin/users/" + admin.id() + "/roles/" + adminRoleId()).ok()).isTrue();
    }

    @Test
    void deletingARoleRemovesItFromItsMembers() throws Exception {
        Session admin = loggedInAdmin("joshua");
        UUID member = createUser("anna", "anna@example.com", PASSWORD, true);
        UUID role = createRole(admin.browser(), "Helpers");
        admin.browser().put("/api/admin/roles/" + role + "/permissions/links.create", Map.of());
        admin.browser().put("/api/admin/users/" + member + "/roles/" + role, Map.of());
        assertThat(permissions.has(member, "links.create")).isTrue();
        assertThat(admin.browser().delete("/api/admin/roles/" + role).ok()).isTrue();
        assertThat(permissions.has(member, "links.create")).isFalse();
    }

    @Test
    void managingRolesNeedsTheRoleRight() throws Exception {
        Session viewer = loggedInWithPermissions("anna", true, "admin.users.view");
        assertThat(viewer.browser().get("/api/admin/roles").ok()).isTrue(); // Lesen für die Benutzeransicht
        assertThat(viewer.browser().post("/api/admin/roles", Map.of("name", "Helpers")).status()).isEqualTo(403);
    }

    @Test
    void roleChangesAreWrittenToTheAuditLog() throws Exception {
        Session admin = loggedInAdmin("joshua");
        UUID role = createRole(admin.browser(), "Helpers");
        admin.browser().delete("/api/admin/roles/" + role);
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action LIKE 'role.%' ORDER BY id")
                .query(String.class).list()).containsExactly("role.created", "role.deleted");
    }
}
