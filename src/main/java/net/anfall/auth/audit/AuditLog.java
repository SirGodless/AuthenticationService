package net.anfall.auth.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Protokoll. Nur anhängen. In details nie Passwörter, Tokens, Geheimnisse oder Codes eintragen. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID actorId, UUID subjectId, String action, Map<String, Object> details, String ip) {
        jdbc.sql("""
                        INSERT INTO audit_log (actor_id, subject_id, action, details, ip)
                        VALUES (:actor, :subject, :action, CAST(:details AS jsonb), :ip)
                        """)
                .param("actor", actorId)
                .param("subject", subjectId)
                .param("action", action)
                .param("details", json.writeValueAsString(details))
                .param("ip", ip)
                .update();
    }
}
