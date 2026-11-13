package net.anfall.auth.account;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.oidc.TokenRevocationService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bann und Entbannen (Abschnitt 7 der Spezifikation). Ein Bann blockiert Anmeldung und Dienste, Konto und Daten bleiben,
 * die E-Mail bleibt reserviert. Er beendet alle Sitzungen und widerruft alle Refresh-Tokens. Das Deaktivieren offener
 * Einladungslinks kommt mit den Einladungslinks (Etappe 5). Die Oberfläche dazu folgt mit dem Dashboard (Etappe 4).
 */
@Service
public class AccountBanService {

    private final JdbcClient jdbc;
    private final SessionService sessions;
    private final TokenRevocationService tokens;
    private final AuditLog audit;

    public AccountBanService(JdbcClient jdbc, SessionService sessions, TokenRevocationService tokens, AuditLog audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional
    public void ban(UUID actorId, UUID targetId, String ip) {
        if (actorId.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_ban_self");
        }
        if (isLastAdmin(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_ban_last_admin");
        }
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", targetId).update();
        int endedSessions = sessions.revokeAll(targetId);
        int revokedTokens = tokens.revokeAll(targetId);
        audit.record(actorId, targetId, "account.banned",
                Map.of("sessions_ended", endedSessions, "authorizations_revoked", revokedTokens), ip);
    }

    @Transactional
    public void unban(UUID actorId, UUID targetId, String ip) {
        jdbc.sql("UPDATE users SET banned = false WHERE id = :id").param("id", targetId).update();
        audit.record(actorId, targetId, "account.unbanned", Map.of(), ip);
    }

    /** Ist das Konto der einzige nicht gebannte Admin? Dann darf es nicht gebannt werden. */
    private boolean isLastAdmin(UUID userId) {
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
