package net.anfall.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Logins landen im Protokoll, ohne Geheimnisse. */
class LoginAuditTest extends IntegrationTestBase {

    // ---- Protokoll ----

    @Test
    void loginsAreAuditedWithIpButWithoutSecrets() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        login(browser().from("203.0.113.7"), "joshua", "wrong password entirely");
        login(browser().from("203.0.113.7"), "joshua", PASSWORD);
        browser().from("203.0.113.8").post("/api/auth/login", creds("niemand", "wrong password entirely", false));

        var rows = jdbc.sql("SELECT action, subject_id, ip, details::text AS details FROM audit_log ORDER BY id")
                .query().listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("login.failed", "login.success", "login.failed");
        assertThat(rows.get(0)).containsEntry("ip", "203.0.113.7").containsEntry("subject_id", id);
        assertThat(rows.get(2)).containsEntry("ip", "203.0.113.8").containsEntry("subject_id", null);

        String everything = jdbc.sql("SELECT string_agg(action || coalesce(details::text,'') || coalesce(ip,''), ' ') "
                + "FROM audit_log").query(String.class).single();
        assertThat(everything).doesNotContain(PASSWORD).doesNotContain("wrong password")
                .doesNotContain("niemand"); // die eingegebene Kennung wird nicht protokolliert
    }
}
