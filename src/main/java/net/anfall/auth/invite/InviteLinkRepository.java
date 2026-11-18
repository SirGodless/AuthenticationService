package net.anfall.auth.invite;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL für Einladungslinks. Links werden nie gelöscht. */
@Repository
public class InviteLinkRepository {

    private static final String SELECT = """
            SELECT l.id, l.creator_id, c.username AS creator_name, l.role_id, l.role_name, l.role_permissions::text AS role_permissions,
                   l.direct_permissions::text AS direct_permissions, l.privileged, l.created_at, l.expires_at, l.status,
                   l.status_reason, u.username AS used_by_name, l.granted::text AS granted
            FROM invite_links l JOIN users c ON c.id = l.creator_id LEFT JOIN users u ON u.id = l.used_by
            """;

    private static final TypeReference<List<Grant>> GRANTS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public InviteLinkRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(UUID id, String tokenHash, UUID creator, UUID roleId, String roleName, List<Grant> rolePermissions,
                       List<Grant> direct, boolean privileged, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO invite_links (id, token_hash, creator_id, role_id, role_name, role_permissions,
                                                  direct_permissions, privileged, expires_at)
                        VALUES (:id, :h, :c, :r, :rn, CAST(:rp AS jsonb), CAST(:dp AS jsonb), :p, :e)
                        """)
                .param("id", id).param("h", tokenHash).param("c", creator).param("r", roleId).param("rn", roleName)
                .param("rp", json.writeValueAsString(rolePermissions)).param("dp", json.writeValueAsString(direct))
                .param("p", privileged).param("e", java.sql.Timestamp.from(expiresAt)).update();
    }

    public boolean existsByTokenHash(String hash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM invite_links WHERE token_hash = :h)").param("h", hash)
                .query(Boolean.class).single();
    }

    /** Sperrt die Zeile bis zum Ende der Transaktion: Gleichzeitiges Einlösen wird so nacheinander abgearbeitet. */
    public Optional<InviteLink> lockByTokenHash(String hash) {
        return jdbc.sql(SELECT + " WHERE l.token_hash = :h FOR UPDATE OF l").param("h", hash).query(this::map).optional();
    }

    public Optional<InviteLink> findByTokenHash(String hash) {
        return jdbc.sql(SELECT + " WHERE l.token_hash = :h").param("h", hash).query(this::map).optional();
    }

    public Optional<InviteLink> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE l.id = :id").param("id", id).query(this::map).optional();
    }

    public List<InviteLink> listByCreator(UUID creator) {
        return jdbc.sql(SELECT + " WHERE l.creator_id = :c ORDER BY l.created_at DESC").param("c", creator).query(this::map).list();
    }

    public List<InviteLink> listAll() {
        return jdbc.sql(SELECT + " ORDER BY l.created_at DESC LIMIT 500").query(this::map).list();
    }

    /** Gleichzeitig aktive Links: nicht benutzt, nicht deaktiviert, nicht abgelaufen. */
    public long activeCount(UUID creator) {
        return jdbc.sql("SELECT count(*) FROM invite_links WHERE creator_id = :c AND status = 'active' AND expires_at > now()")
                .param("c", creator).query(Long.class).single();
    }

    /** Sperrt die Zeile des Users, damit zwei gleichzeitige Anfragen das Limit nicht beide überschreiten. */
    public void lockCreator(UUID creator) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE").param("id", creator).query(UUID.class).optional();
    }

    public void markUsed(UUID id, UUID usedBy, String grantedJson) {
        jdbc.sql("""
                        UPDATE invite_links SET status = 'used', used_by = :u, granted = CAST(:g AS jsonb),
                                                status_changed_at = now() WHERE id = :id
                        """).param("id", id).param("u", usedBy).param("g", grantedJson).update();
    }

    /** Deaktiviert einen noch aktiven Link. Gibt zurück, ob etwas geändert wurde. */
    public boolean deactivate(UUID id, String reason) {
        return jdbc.sql("""
                        UPDATE invite_links SET status = 'deactivated', status_reason = :r, status_changed_at = now()
                        WHERE id = :id AND status = 'active'
                        """).param("id", id).param("r", reason).update() > 0;
    }

    public int deactivateAllActiveOf(UUID creator, String reason) {
        return jdbc.sql("""
                        UPDATE invite_links SET status = 'deactivated', status_reason = :r, status_changed_at = now()
                        WHERE creator_id = :c AND status = 'active'
                        """).param("c", creator).param("r", reason).update();
    }

    private InviteLink map(ResultSet rs, int row) throws SQLException {
        return new InviteLink(rs.getObject("id", UUID.class), rs.getObject("creator_id", UUID.class),
                rs.getString("creator_name"), rs.getObject("role_id", UUID.class), rs.getString("role_name"),
                json.readValue(rs.getString("role_permissions"), GRANTS), json.readValue(rs.getString("direct_permissions"), GRANTS),
                rs.getBoolean("privileged"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getString("status"), rs.getString("status_reason"), rs.getString("used_by_name"), rs.getString("granted"));
    }
}
