package net.anfall.auth.account;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Die Regel "Der letzte Admin darf nicht verschwinden": gilt für Bann und für das Entfernen der Rolle Admin. */
@Component
public class LastAdminRule {

    private final JdbcClient jdbc;

    public LastAdminRule(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ist das Konto ein Admin und der einzige nicht gebannte? */
    public boolean isLastAdmin(UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                       WHERE ur.user_id = :u AND r.all_admin_permissions)
                           AND NOT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                           JOIN users u ON u.id = ur.user_id
                                           WHERE r.all_admin_permissions AND NOT u.banned AND u.id <> :u)
                        """)
                .param("u", userId).query(Boolean.class).single();
    }
}
