package net.anfall.auth.permission;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** Eigene Rechte anlegen und verwalten, direkte Rechte an User vergeben. */
class PermissionAdminApiTest extends AdminTestBase {

    private static Map<String, Object> def(String key, String type, String direction, boolean unlimited) {
        return Map.of("key", key, "type", type, "direction", direction, "allowUnlimited", unlimited,
                "linkGrantable", false, "description", "Test");
    }

    @Test
    void anAdminCreatesABooleanAndANumberPermission() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.post("/api/admin/permissions", def("media.upload", "boolean", "", false)).ok()).isTrue();
        assertThat(admin.post("/api/admin/permissions", def("media.quota_gb", "number", "higher", true)).ok()).isTrue();
        String list = admin.get("/api/admin/permissions").body();
        assertThat(list).contains("media.upload", "media.quota_gb");
    }

    @Test
    void keyRulesAreEnforced() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.post("/api/admin/permissions", def("Bad Key", "boolean", "", false)).status()).isEqualTo(400);
        assertThat(admin.post("/api/admin/permissions", def("nodot", "boolean", "", false)).status()).isEqualTo(400);
        // admin.* und service.* sind reserviert
        assertThat(admin.post("/api/admin/permissions", def("admin.mine", "boolean", "", false)).body()).contains("permission_key_reserved");
        assertThat(admin.post("/api/admin/permissions", def("service.mine", "boolean", "", false)).body()).contains("permission_key_reserved");
        assertThat(admin.post("/api/admin/permissions", def("media.upload", "boolean", "", false)).ok()).isTrue();
        assertThat(admin.post("/api/admin/permissions", def("media.upload", "boolean", "", false)).status()).isEqualTo(409);
    }

    @Test
    void typeAndDirectionRulesAreEnforced() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.post("/api/admin/permissions", def("media.a", "text", "", false)).body()).contains("permission_type_invalid");
        assertThat(admin.post("/api/admin/permissions", def("media.b", "number", "", false)).body()).contains("direction_required");
        assertThat(admin.post("/api/admin/permissions", def("media.c", "boolean", "higher", false)).body()).contains("boolean_has_no_direction");
        // "unbegrenzt" gibt es nur bei "höher = mehr"
        assertThat(admin.post("/api/admin/permissions", def("media.d", "number", "lower", true)).body()).contains("unlimited_only_for_higher");
    }

    @Test
    void builtInPermissionsCannotBeDeletedButCustomOnesCanUntilTheyAreInUse() throws Exception {
        Session admin = loggedInAdmin("joshua");
        Browser b = admin.browser();
        assertThat(b.delete("/api/admin/permissions/links.create").status()).isEqualTo(400);
        b.post("/api/admin/permissions", def("media.upload", "boolean", "", false));
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        assertThat(b.put("/api/admin/users/" + anna + "/permissions/media.upload", Map.of()).ok()).isTrue();
        assertThat(b.delete("/api/admin/permissions/media.upload").status()).isEqualTo(409); // noch in Verwendung
        assertThat(b.delete("/api/admin/users/" + anna + "/permissions/media.upload").ok()).isTrue();
        assertThat(b.delete("/api/admin/permissions/media.upload").ok()).isTrue();
    }

    @Test
    void descriptionAndLinkGrantableCanBeChanged() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        admin.post("/api/admin/permissions", def("media.upload", "boolean", "", false));
        assertThat(admin.patch("/api/admin/permissions/media.upload", Map.of("description", "Neu", "linkGrantable", true)).ok()).isTrue();
        assertThat(jdbc.sql("SELECT link_grantable FROM permissions WHERE key = 'media.upload'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void directPermissionsAreCombinedWithRolePermissions() throws Exception {
        Session admin = loggedInAdmin("joshua");
        Browser b = admin.browser();
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        b.put("/api/admin/users/" + anna + "/permissions/links.max_active", Map.of("number", 1));
        String detail = b.get("/api/admin/users/" + anna).body();
        assertThat(detail).contains("links.max_active");
        assertThat(b.put("/api/admin/users/" + anna + "/permissions/links.max_active", Map.of("unlimited", true)).ok()).isTrue();
        assertThat(b.put("/api/admin/users/" + anna + "/permissions/links.max_active", Map.of("number", -1)).status()).isEqualTo(400);
    }

    @Test
    void managingPermissionsNeedsTheManageRight() throws Exception {
        Browser roleManager = loggedInWithPermissions("anna", true, "admin.roles.manage").browser();
        assertThat(roleManager.get("/api/admin/permissions").ok()).isTrue(); // Liste für den Rollen-Dialog
        assertThat(roleManager.post("/api/admin/permissions", def("media.upload", "boolean", "", false)).status()).isEqualTo(403);
    }
}
