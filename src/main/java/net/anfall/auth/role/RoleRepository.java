package net.anfall.auth.role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL für Rollen und ihre Mitglieder. Die Werte der Rechte in Rollen stehen im PermissionRepository. */
@Component
public class RoleRepository {

    private final JdbcClient jdbc;

    public RoleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Role> findAll() {
        return jdbc.sql("SELECT id, name, builtin, all_admin_permissions FROM roles ORDER BY name")
                .query(RoleRepository::map).list();
    }

    public Optional<Role> find(UUID id) {
        return jdbc.sql("SELECT id, name, builtin, all_admin_permissions FROM roles WHERE id = :id").param("id", id)
                .query(RoleRepository::map).optional();
    }

    public boolean nameExists(String name, UUID exceptId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM roles WHERE lower(name) = lower(:n) AND id <> :id)")
                .param("n", name).param("id", exceptId == null ? new UUID(0, 0) : exceptId)
                .query(Boolean.class).single();
    }

    public void insert(UUID id, String name) {
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:id, :n)").param("id", id).param("n", name).update();
    }

    public void rename(UUID id, String name) {
        jdbc.sql("UPDATE roles SET name = :n WHERE id = :id").param("n", name).param("id", id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM role_permissions WHERE role_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM user_roles WHERE role_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM roles WHERE id = :id").param("id", id).update();
    }

    public long memberCount(UUID roleId) {
        return jdbc.sql("SELECT count(*) FROM user_roles WHERE role_id = :r").param("r", roleId).query(Long.class).single();
    }

    public List<Role> rolesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT r.id, r.name, r.builtin, r.all_admin_permissions
                        FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = :u ORDER BY r.name
                        """).param("u", userId).query(RoleRepository::map).list();
    }

    public void assign(UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r) ON CONFLICT DO NOTHING")
                .param("u", userId).param("r", roleId).update();
    }

    public void unassign(UUID userId, UUID roleId) {
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :u AND role_id = :r").param("u", userId).param("r", roleId).update();
    }

    private static Role map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Role(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("builtin"),
                rs.getBoolean("all_admin_permissions"));
    }
}
