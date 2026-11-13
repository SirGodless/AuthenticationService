package net.anfall.auth.admincli;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

class AdminCliTest extends IntegrationTestBase {

    @Autowired ConfigurableApplicationContext context;
    @Autowired SessionService sessions;
    @Autowired AuditLog audit;
    @Autowired TransactionTemplate tx;

    private int run(String command, String username) {
        return new AdminCli(context, jdbc, sessions, audit, tx, command, username).execute();
    }

    @Test
    void reset2faRemovesTotpAndRecoveryCodesAndEndsSessions() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        UUID other = createUser("anna", "anna@example.com", PASSWORD, true);
        enableTotp(id);
        addRecoveryCodes(id);
        enableTotp(other);
        sessions.create(id, false, false);

        assertThat(run("reset-2fa", "  JOSHUA ")).isZero();

        assertThat(jdbc.sql("SELECT count(*) FROM user_totp WHERE user_id = :u").param("u", id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u").param("u", id)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM login_sessions WHERE user_id = :u").param("u", id)
                .query(Long.class).single()).isZero();
        // andere Konten bleiben unberührt
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp WHERE user_id = :u").param("u", other)
                .query(Long.class).single()).isEqualTo(1);
        // Danach reicht das Passwort für den Login
        assertThat(browser().post("/api/auth/login", Map.of("identifier", "joshua", "password", PASSWORD))
                .body()).contains("\"status\":\"ok\"");
        assertThat(jdbc.sql("SELECT action, ip FROM audit_log WHERE action = 'admin.cli.reset_2fa'")
                .query().singleRow()).containsEntry("ip", "cli");
    }

    @Test
    void unknownUserAndUnknownCommandFailWithoutChangingAnything() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        assertThat(run("reset-2fa", "niemand")).isEqualTo(1);
        assertThat(run("irgendwas", "joshua")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp").query(Long.class).single()).isEqualTo(1);
    }
}
