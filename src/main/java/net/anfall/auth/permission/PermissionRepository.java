package net.anfall.auth.permission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL für Rechte: Definitionen und die Werte in Rollen und bei Usern. */
@Component
public class PermissionRepository {

    /** Ein gesetzter Wert für ein Recht aus einer Quelle. */
    public record ValueRow(String permissionKey, PermissionValue value) {
    }

    private static final String DEF_COLUMNS = "key, type, direction, allow_unlimited, link_grantable, builtin, description";

    private final JdbcClient jdbc;

    public PermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Definitionen ----

    public List<PermissionDefinition> findAll() {
        return jdbc.sql("SELECT " + DEF_COLUMNS + " FROM permissions ORDER BY key")
                .query(PermissionRepository::definition).list();
    }

    public Optional<PermissionDefinition> find(String key) {
        return jdbc.sql("SELECT " + DEF_COLUMNS + " FROM permissions WHERE key = :k").param("k", key)
                .query(PermissionRepository::definition).optional();
    }

    public void insert(PermissionDefinition d) {
        jdbc.sql("""
                        INSERT INTO permissions (key, type, direction, allow_unlimited, link_grantable, builtin, description)
                        VALUES (:k, :t, :d, :u, :l, false, :desc)
                        """)
                .param("k", d.key()).param("t", d.type()).param("d", d.direction()).param("u", d.allowUnlimited())
                .param("l", d.linkGrantable()).param("desc", d.description()).update();
    }

    public void update(String key, String description, boolean linkGrantable) {
        jdbc.sql("UPDATE permissions SET description = :d, link_grantable = :l WHERE key = :k")
                .param("d", description).param("l", linkGrantable).param("k", key).update();
    }

    public void delete(String key) {
        jdbc.sql("DELETE FROM permissions WHERE key = :k").param("k", key).update();
    }

    /** Wie viele Rollen und User dieses Recht gesetzt haben. */
    public long valueCount(String key) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM role_permissions WHERE permission_key = :k)
                             + (SELECT count(*) FROM user_permissions WHERE permission_key = :k)
                        """).param("k", key).query(Long.class).single();
    }

    /** Dienste, die dieses Recht als Anmeldevoraussetzung verlangen. Nur dort hat ein Recht Wirkung. */
    public List<String> servicesRequiring(String key) {
        return jdbc.sql("SELECT key FROM services WHERE required_permission = :k ORDER BY key").param("k", key)
                .query(String.class).list();
    }

    // ---- Werte ----

    /** Direkt vergebene Werte eines Users. */
    public List<ValueRow> userValues(UUID userId) {
        return jdbc.sql("SELECT permission_key, number_value, unlimited FROM user_permissions WHERE user_id = :u")
                .param("u", userId).query(PermissionRepository::valueRow).list();
    }

    /** Werte aus allen Rollen eines Users (ein Recht kann mehrfach vorkommen, eine Zeile je Rolle). */
    public List<ValueRow> valuesFromRolesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT rp.permission_key, rp.number_value, rp.unlimited
                        FROM user_roles ur JOIN role_permissions rp ON rp.role_id = ur.role_id
                        WHERE ur.user_id = :u
                        """).param("u", userId).query(PermissionRepository::valueRow).list();
    }

    public List<ValueRow> roleValues(UUID roleId) {
        return jdbc.sql("SELECT permission_key, number_value, unlimited FROM role_permissions WHERE role_id = :r")
                .param("r", roleId).query(PermissionRepository::valueRow).list();
    }

    /** Hat der User eine Rolle, die alle Verwaltungsrechte enthält (die eingebaute Rolle Admin)? */
    public boolean hasAllAdminRole(UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                       WHERE ur.user_id = :u AND r.all_admin_permissions)
                        """).param("u", userId).query(Boolean.class).single();
    }

    public void setUserValue(UUID userId, String key, PermissionValue v) {
        jdbc.sql("""
                        INSERT INTO user_permissions (user_id, permission_key, number_value, unlimited)
                        VALUES (:u, :k, :n, :x)
                        ON CONFLICT (user_id, permission_key) DO UPDATE SET number_value = :n, unlimited = :x
                        """)
                .param("u", userId).param("k", key).param("n", v.number()).param("x", v.unlimited()).update();
    }

    public void removeUserValue(UUID userId, String key) {
        jdbc.sql("DELETE FROM user_permissions WHERE user_id = :u AND permission_key = :k")
                .param("u", userId).param("k", key).update();
    }

    public void setRoleValue(UUID roleId, String key, PermissionValue v) {
        jdbc.sql("""
                        INSERT INTO role_permissions (role_id, permission_key, number_value, unlimited)
                        VALUES (:r, :k, :n, :x)
                        ON CONFLICT (role_id, permission_key) DO UPDATE SET number_value = :n, unlimited = :x
                        """)
                .param("r", roleId).param("k", key).param("n", v.number()).param("x", v.unlimited()).update();
    }

    public void removeRoleValue(UUID roleId, String key) {
        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :r AND permission_key = :k")
                .param("r", roleId).param("k", key).update();
    }

    private static PermissionDefinition definition(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PermissionDefinition(rs.getString("key"), rs.getString("type"), rs.getString("direction"),
                rs.getBoolean("allow_unlimited"), rs.getBoolean("link_grantable"), rs.getBoolean("builtin"),
                rs.getString("description"));
    }

    private static ValueRow valueRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        long number = rs.getLong("number_value");
        boolean hasNumber = !rs.wasNull();
        return new ValueRow(rs.getString("permission_key"),
                new PermissionValue(true, hasNumber ? number : null, rs.getBoolean("unlimited")));
    }
}
