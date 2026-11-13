package net.anfall.auth.permission;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Berechtigungen eines Users. Vorerst nur boolesche Rechte (Etappe 3). Die Neuberechnung mit Zahlenrechten, Richtung
 * und "unbegrenzt" kommt mit Etappe 4 und ersetzt {@link #has}.
 *
 * Ein User hat ein Recht, wenn er es direkt bekommen hat, über eine seiner Rollen oder, bei Rechten mit Präfix
 * "admin.", über eine Rolle, die alle Verwaltungsrechte enthält (die eingebaute Rolle Admin).
 */
@Service
public class PermissionService {

    private final JdbcClient jdbc;

    public PermissionService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean has(UUID userId, String permissionKey) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_permissions WHERE user_id = :u AND permission_key = :p)
                            OR EXISTS (SELECT 1 FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id
                                       WHERE ur.user_id = :u AND rp.permission_key = :p)
                            OR (:p LIKE 'admin.%' AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                                              WHERE ur.user_id = :u AND r.all_admin_permissions))
                        """)
                .param("u", userId).param("p", permissionKey)
                .query(Boolean.class).single();
    }

    public void grantToUser(UUID userId, String permissionKey) {
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key) VALUES (:u, :p) ON CONFLICT DO NOTHING")
                .param("u", userId).param("p", permissionKey).update();
    }

    public void revokeFromUser(UUID userId, String permissionKey) {
        jdbc.sql("DELETE FROM user_permissions WHERE user_id = :u AND permission_key = :p")
                .param("u", userId).param("p", permissionKey).update();
    }
}
