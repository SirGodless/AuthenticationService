package net.anfall.auth.admincli;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.oidc.TokenRevocationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Entfernt die 2FA eines Kontos, z. B. für den letzten Admin, der sich ausgesperrt hat. */
@Component
class Reset2faCommand implements AdminCommand {

    private final JdbcClient jdbc;
    private final SessionService sessions;
    private final TokenRevocationService tokens;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    Reset2faCommand(JdbcClient jdbc, SessionService sessions, TokenRevocationService tokens, AuditLog audit,
                    TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
        this.tx = tx;
    }

    @Override
    public String name() {
        return "reset-2fa";
    }

    @Override
    public String usage() {
        return "reset-2fa <username>   entfernt 2FA und Wiederherstellungscodes, beendet alle Sitzungen";
    }

    @Override
    public int run(List<String> args, PrintStream out) {
        if (args.size() != 1) {
            System.err.println("Aufruf: " + usage());
            return 2;
        }
        String name = args.get(0).trim().toLowerCase(Locale.ROOT);
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
            tokens.revokeAll(userId);
            // Kein handelnder Account: Der Befehl läuft auf dem Server, nicht im Hub
            audit.record(null, userId, "admin.cli.reset_2fa", Map.of(), "cli");
        });
        out.println("2FA und Wiederherstellungscodes von '" + name + "' wurden entfernt, alle Sitzungen und Tokens "
                + "beendet. Beim nächsten Login reicht das Passwort. Richte die 2FA danach neu ein.");
        return 0;
    }
}
