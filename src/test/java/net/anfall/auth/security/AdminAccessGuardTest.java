package net.anfall.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** Zugriff auf Admin-Funktionen: angemeldet, Recht vorhanden und 2FA eingerichtet (CLAUDE.md Abschnitt 3 und 5). */
class AdminAccessGuardTest extends AdminTestBase {

    @Test
    void withoutLoginTheAdminApiAnswers401() throws Exception {
        assertThat(browser().get("/api/admin/users").status()).isEqualTo(401);
        assertThat(browser().get("/api/admin/overview").status()).isEqualTo(401);
    }

    @Test
    void withoutTheRightTheAnswerIs403Forbidden() throws Exception {
        Session user = loggedInWithPermissions("anna", false);
        Browser.Result r = user.browser().get("/api/admin/users");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("\"forbidden\"");
    }

    @Test
    void anAdminRightWithoutTwoFactorIsRefusedWithTwoFactorRequired() throws Exception {
        Session user = loggedInWithPermissions("anna", false, "admin.users.view");
        Browser.Result r = user.browser().get("/api/admin/users");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("two_factor_required");
    }

    @Test
    void withTheRightAndTwoFactorTheCallWorks() throws Exception {
        Session user = loggedInWithPermissions("anna", true, "admin.users.view");
        assertThat(user.browser().get("/api/admin/users").ok()).isTrue();
        // ein anderes Recht fehlt weiterhin
        assertThat(user.browser().get("/api/admin/audit").status()).isEqualTo(403);
    }

    @Test
    void theOverviewListsOnlyTheSectionsTheUserMayOpen() throws Exception {
        Session user = loggedInWithPermissions("anna", true, "admin.users.view", "admin.audit.view");
        String body = user.browser().get("/api/admin/overview").body();
        assertThat(body).contains("users", "audit").doesNotContain("\"roles\"", "\"permissions\"");
        assertThat(body).contains("\"isAdmin\":true", "\"twoFactorEnabled\":true");
    }

    @Test
    void theOverviewWorksWithoutTwoFactorSoThePageCanPointToTheSetup() throws Exception {
        Session user = loggedInWithPermissions("anna", false, "admin.users.view");
        Browser.Result r = user.browser().get("/api/admin/overview");
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains("\"twoFactorEnabled\":false");
    }

    @Test
    void theAdminRoleGivesAllAdminSectionsButNoServiceAccess() throws Exception {
        Session admin = loggedInAdmin("joshua");
        String body = admin.browser().get("/api/admin/overview").body();
        assertThat(body).contains("users", "roles", "permissions", "audit");
    }
}
