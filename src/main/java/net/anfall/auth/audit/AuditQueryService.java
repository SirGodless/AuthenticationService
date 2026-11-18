package net.anfall.auth.audit;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Das Protokoll lesen (Dashboard, Recht admin.audit.view). Neueste Einträge zuerst, seitenweise. */
@Service
public class AuditQueryService {

    /** Eintrag mit aufgelösten Usernamen. Details enthalten nie Geheimnisse (siehe AuditLog). */
    public record Entry(long id, Instant at, String actor, String subject, String action, String details, String ip) {
    }

    public static final int MAX_LIMIT = 100;

    private final JdbcClient jdbc;

    public AuditQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param action Beginn der Aktion, z. B. "login" findet login.success und login.failed (leer = alle)
     * @param username Username des handelnden oder betroffenen Accounts (leer = alle)
     * @param beforeId nur Einträge mit kleinerer Nummer (zum Weiterblättern), null = von vorn
     */
    public List<Entry> query(String action, String username, Long beforeId, int limit) {
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        String actionPrefix = (action == null ? "" : action.trim().replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_")) + "%";
        String user = username == null ? "" : username.trim().toLowerCase();
        return jdbc.sql("""
                        SELECT a.id, a.at, actor.username AS actor, subject.username AS subject, a.action,
                               a.details::text AS details, a.ip
                        FROM audit_log a
                        LEFT JOIN users actor ON actor.id = a.actor_id
                        LEFT JOIN users subject ON subject.id = a.subject_id
                        WHERE a.action LIKE :action
                          AND (:user = '' OR actor.username = :user OR subject.username = :user)
                          AND (CAST(:before AS bigint) IS NULL OR a.id < CAST(:before AS bigint))
                        ORDER BY a.id DESC LIMIT :limit
                        """)
                .param("action", actionPrefix).param("user", user).param("before", beforeId).param("limit", max)
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("actor"),
                        rs.getString("subject"), rs.getString("action"), rs.getString("details"), rs.getString("ip")))
                .list();
    }
}
