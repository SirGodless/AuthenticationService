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
    private final LastAdminRule lastAdmin;

    public AccountBanService(JdbcClient jdbc, SessionService sessions, TokenRevocationService tokens, AuditLog audit,
                             LastAdminRule lastAdmin) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
        this.lastAdmin = lastAdmin;
    }

    @Transactional
    public void ban(UUID actorId, UUID targetId, String ip) {
        if (actorId.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_ban_self");
        }
        if (lastAdmin.isLastAdmin(targetId)) {
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
}
