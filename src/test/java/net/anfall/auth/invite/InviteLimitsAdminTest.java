package net.anfall.auth.invite;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.InviteTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Grenzen der Link-Gültigkeit im Dashboard einstellen, Liste aller Links für Admins. */
class InviteLimitsAdminTest extends InviteTestBase {

    private static Map<String, Object> limits(int min, int def, int max, int privileged) {
        return Map.of("minHours", min, "defaultDays", def, "maxDays", max, "privilegedMaxDays", privileged);
    }

    @Test
    void defaultsAreOneHourSevenDaysThirtyDaysAndThreeDaysWithRights() throws Exception {
        Session admin = loggedInAdmin("joshua");
        JsonNode s = json.readTree(admin.browser().get("/api/admin/settings/invites").body());
        assertThat(s.get("limits").toString()).contains("\"minHours\":1", "\"defaultDays\":7", "\"maxDays\":30", "\"privilegedMaxDays\":3");
        assertThat(s.get("hardMaxDays").asInt()).isEqualTo(30);
    }

    @Test
    void changedLimitsApplyToNewLinks() throws Exception {
        Session admin = loggedInAdmin("joshua");
        assertThat(admin.browser().put("/api/admin/settings/invites", limits(2, 5, 10, 1)).ok()).isTrue();
        Session anna = creator("anna", 9);
        assertThat(tryCreate(anna, body(1, null, null)).body()).contains("validity_too_short");
        assertThat(tryCreate(anna, body(11 * 24, null, null)).body()).contains("validity_too_long");
        assertThat(tryCreate(anna, body(10 * 24, null, null)).ok()).isTrue();
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action = 'settings.invite_limits_changed'").query(String.class).list()).hasSize(1);
    }

    @Test
    void theMonthIsAHardCeilingAndValuesMustMakeSense() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.put("/api/admin/settings/invites", limits(1, 7, 31, 3)).body()).contains("invite_limits_invalid");
        assertThat(admin.put("/api/admin/settings/invites", limits(0, 7, 30, 3)).status()).isEqualTo(400);
        assertThat(admin.put("/api/admin/settings/invites", limits(1, 8, 7, 3)).status()).isEqualTo(400);   // Standard über dem Maximum
        assertThat(admin.put("/api/admin/settings/invites", limits(1, 7, 30, 31)).status()).isEqualTo(400); // mit Rechten länger als sonst
        assertThat(admin.put("/api/admin/settings/invites", limits(100, 3, 30, 3)).status()).isEqualTo(400); // Minimum über dem Standard
    }

    @Test
    void settingsNeedTheSettingsRightAndTwoFactor() throws Exception {
        assertThat(loggedInWithPermissions("anna", true, "admin.users.view").browser().get("/api/admin/settings/invites").status()).isEqualTo(403);
        Browser noTwoFactor = loggedInWithPermissions("bob", false, "admin.settings.manage").browser();
        assertThat(noTwoFactor.get("/api/admin/settings/invites").body()).contains("two_factor_required");
        Browser ok = loggedInWithPermissions("carl", true, "admin.settings.manage").browser();
        assertThat(ok.get("/api/admin/settings/invites").ok()).isTrue();
        assertThat(ok.put("/api/admin/settings/invites", limits(1, 7, 30, 3)).ok()).isTrue();
    }

    @Test
    void adminsSeeAllLinksAndCanDeactivateAny() throws Exception {
        Session anna = creator("anna", 5);
        Session bob = creator("bob", 5);
        var l1 = tryCreate(anna, body(null, null, null));
        tryCreate(bob, body(null, null, null));
        Browser admin = loggedInWithPermissions("view", true, "admin.links.view_all").browser();
        JsonNode all = json.readTree(admin.get("/api/admin/invites").body());
        assertThat(all).hasSize(2);
        assertThat(all.toString()).contains("anna", "bob").doesNotContain("token");
        assertThat(admin.post("/api/admin/invites/" + linkIdOf(l1) + "/deactivate", Map.of()).ok()).isTrue();
        assertThat(linkStatus(linkIdOf(l1))).isEqualTo("deactivated");
        assertThat(jdbc.sql("SELECT status_reason FROM invite_links WHERE id = :i").param("i", linkIdOf(l1)).query(String.class).single()).isEqualTo("admin");
        // ohne das Recht nicht
        assertThat(loggedInWithPermissions("anna2", true, "admin.users.view").browser().get("/api/admin/invites").status()).isEqualTo(403);
    }
}
