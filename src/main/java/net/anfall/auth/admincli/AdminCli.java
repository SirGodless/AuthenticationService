package net.anfall.auth.admincli;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Befehle für den Betreiber im Backend-Container, wenn kein Admin mehr in die Oberfläche kommt.
 * Aufruf (siehe README): docker compose exec auth-backend auth-admin reset-2fa <username>
 * Läuft ohne Webserver und beendet sich danach selbst.
 */
@Component
@Order(2)
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${app.cli.command:}'.isEmpty()")
class AdminCli implements ApplicationRunner {

    private final ConfigurableApplicationContext context;
    private final JdbcClient jdbc;
    private final SessionService sessions;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final String command;
    private final String username;

    AdminCli(ConfigurableApplicationContext context, JdbcClient jdbc, SessionService sessions, AuditLog audit,
             TransactionTemplate tx, @Value("${app.cli.command:}") String command,
             @Value("${app.cli.username:}") String username) {
        this.context = context;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
        this.tx = tx;
        this.command = command;
        this.username = username;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            code = execute();
        } catch (RuntimeException e) {
            System.err.println("Fehler: " + e.getMessage());
            code = 1;
        }
        final int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    int execute() {
        if (!"reset-2fa".equals(command)) {
            System.err.println("Unbekannter Befehl '" + command + "'. Verfügbar: reset-2fa <username>");
            return 2;
        }
        String name = username.trim().toLowerCase(java.util.Locale.ROOT);
        UUID userId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", name)
                .query(UUID.class).optional().orElse(null);
        if (userId == null) {
            System.err.println("Kein Konto mit dem Usernamen '" + name + "'.");
            return 1;
        }
        tx.executeWithoutResult(s -> {
            jdbc.sql("DELETE FROM user_totp WHERE user_id = :u").param("u", userId).update();
            jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
            sessions.revokeAll(userId);
            // Kein handelnder Account: Der Befehl läuft auf dem Server, nicht im Hub
            audit.record(null, userId, "admin.cli.reset_2fa", Map.of(), "cli");
        });
        System.out.println("2FA und Wiederherstellungscodes von '" + name + "' wurden entfernt, alle Sitzungen beendet. "
                + "Beim nächsten Login reicht das Passwort. Richte die 2FA danach neu ein.");
        return 0;
    }
}
