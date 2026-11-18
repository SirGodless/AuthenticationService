package net.anfall.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Protokoll lesen: Recht, Filter, Blättern. */
class AuditAdminApiTest extends AdminTestBase {

    @Test
    void readingTheAuditLogNeedsItsRight() throws Exception {
        Browser noAudit = loggedInWithPermissions("anna", true, "admin.users.view").browser();
        assertThat(noAudit.get("/api/admin/audit").status()).isEqualTo(403);
        Browser withAudit = loggedInWithPermissions("bob", true, "admin.audit.view").browser();
        assertThat(withAudit.get("/api/admin/audit").ok()).isTrue();
    }

    @Test
    void entriesAreNewestFirstAndFilterableByActionAndUser() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        createUser("anna", "anna@example.com", PASSWORD, true);
        login(browser(), "anna", PASSWORD);
        login(browser(), "anna", "wrong wrong wrong wrong");

        JsonNode all = json.readTree(admin.get("/api/admin/audit").body());
        assertThat(all.size()).isGreaterThan(2);
        assertThat(all.get(0).get("id").asLong()).isGreaterThan(all.get(1).get("id").asLong());

        JsonNode logins = json.readTree(admin.get("/api/admin/audit?action=login").body());
        for (JsonNode e : logins) {
            assertThat(e.get("action").asString()).startsWith("login");
        }
        JsonNode ofAnna = json.readTree(admin.get("/api/admin/audit?user=ANNA").body()); // Groß/Klein egal
        assertThat(ofAnna.size()).isGreaterThan(0);
        for (JsonNode e : ofAnna) {
            assertThat(e.toString()).contains("anna");
        }
    }

    @Test
    void pagingWithBeforeAndLimitWorks() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        for (int i = 0; i < 5; i++) {
            admin.post("/api/admin/roles", Map.of("name", "Role" + i));
        }
        JsonNode first = json.readTree(admin.get("/api/admin/audit?action=role&limit=2").body());
        assertThat(first.size()).isEqualTo(2);
        long lastId = first.get(1).get("id").asLong();
        JsonNode next = json.readTree(admin.get("/api/admin/audit?action=role&limit=2&before=" + lastId).body());
        assertThat(next.size()).isEqualTo(2);
        assertThat(next.get(0).get("id").asLong()).isLessThan(lastId);
        // Obergrenze des Limits
        assertThat(json.readTree(admin.get("/api/admin/audit?limit=100000").body()).size()).isLessThanOrEqualTo(100);
    }

    @Test
    void secretsNeverAppearInTheLog() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        String body = admin.get("/api/admin/audit?limit=100").body();
        assertThat(body).doesNotContain(PASSWORD);
    }
}
