package net.anfall.auth.admincli;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.service.ServiceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Gibt einem User das Recht auf einen Dienst (service.&lt;key&gt;). Gedacht für den Anfang, bis das Dashboard da ist. */
@Component
class GrantServiceCommand implements AdminCommand {

    private final JdbcClient jdbc;
    private final ServiceRepository services;
    private final PermissionService permissions;
    private final AuditLog audit;

    GrantServiceCommand(JdbcClient jdbc, ServiceRepository services, PermissionService permissions, AuditLog audit) {
        this.jdbc = jdbc;
        this.services = services;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "grant-service";
    }

    @Override
    public String usage() {
        return "grant-service <username> <key>   gibt dem User Zugang zu einem Dienst";
    }

    @Override
    public int run(List<String> args, PrintStream out) {
        if (args.size() != 2) {
            System.err.println("Aufruf: " + usage());
            return 2;
        }
        String name = args.get(0).trim().toLowerCase(Locale.ROOT);
        UUID userId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", name)
                .query(UUID.class).optional().orElse(null);
        var service = services.findByKey(args.get(1)).orElse(null);
        if (userId == null || service == null) {
            System.err.println(userId == null ? "Kein Konto mit dem Usernamen '" + name + "'."
                    : "Keinen Dienst mit dem Key '" + args.get(1) + "' gefunden.");
            return 1;
        }
        permissions.grantToUser(userId, service.requiredPermission());
        audit.record(null, userId, "permission.granted",
                Map.of("permission", service.requiredPermission(), "via", "cli"), "cli");
        out.println("'" + name + "' hat jetzt Zugang zu " + service.displayName() + ".");
        return 0;
    }
}
