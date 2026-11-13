package net.anfall.auth.admincli;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Reset2faCommandTest extends IntegrationTestBase {

    @Autowired Reset2faCommand command;
    @Autowired SessionService sessions;

    @Test
    void removesTotpAndRecoveryCodesAndEndsSessionsOfThatUserOnly() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        UUID other = createUser("anna", "anna@example.com", PASSWORD, true);
        enableTotp(id);
        addRecoveryCodes(id);
        enableTotp(other);
        sessions.create(id, false, false);

        int[] code = new int[1];
        String out = AdminCliTest.capture(command, List.of("  JOSHUA "), code);

        assertThat(code[0]).isZero();
        assertThat(out).contains("joshua").contains("2FA");
        assertThat(count("user_totp", id)).isZero();
        assertThat(count("recovery_codes", id)).isZero();
        assertThat(count("login_sessions", id)).isZero();
        assertThat(count("user_totp", other)).isEqualTo(1);   // andere Konten bleiben unberührt
        // Danach reicht das Passwort für den Login
        assertThat(browser().post("/api/auth/login", Map.of("identifier", "joshua", "password", PASSWORD))
                .body()).contains("\"status\":\"ok\"");
        assertThat(jdbc.sql("SELECT action, ip FROM audit_log WHERE action = 'admin.cli.reset_2fa'")
                .query().singleRow()).containsEntry("ip", "cli");
    }

    @Test
    void unknownUserAndWrongUsageChangeNothing() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        int[] code = new int[1];
        AdminCliTest.capture(command, List.of("niemand"), code);
        assertThat(code[0]).isEqualTo(1);
        AdminCliTest.capture(command, List.of(), code);
        assertThat(code[0]).isEqualTo(2);
        assertThat(count("user_totp", id)).isEqualTo(1);
    }

    private long count(String table, UUID userId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = :u").param("u", userId)
                .query(Long.class).single();
    }
}
