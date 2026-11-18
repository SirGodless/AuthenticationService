package net.anfall.auth.useradmin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.oidc.TokenRevocationService;
import net.anfall.auth.permission.PermissionRepository;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.role.Role;
import net.anfall.auth.role.RoleRepository;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Benutzerverwaltung im Dashboard: Liste, Details, direkte Rechte, 2FA zurücksetzen. Bann steht im AccountBanService. */
@Service
public class UserAdminService {

    /** Zeile der Benutzerliste. */
    public record UserSummary(UUID id, String username, String email, String language, boolean banned,
                              Instant createdAt, boolean hasTotp, String roles) {
    }

    /** Alles zu einem User für die Detailansicht. */
    public record UserDetail(UserSummary user, List<Role> roles, List<PermissionRepository.ValueRow> directPermissions,
                             Map<String, PermissionValue> effectivePermissions) {
    }

    private final JdbcClient jdbc;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionService permissions;
    private final SessionService sessions;
    private final TokenRevocationService tokens;
    private final AuditLog audit;

    public UserAdminService(JdbcClient jdbc, RoleRepository roleRepository, PermissionRepository permissionRepository,
                            PermissionService permissions, SessionService sessions, TokenRevocationService tokens,
                            AuditLog audit) {
        this.jdbc = jdbc;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.permissions = permissions;
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
    }

    /** Suche in Username und E-Mail, höchstens 200 Treffer. */
    public List<UserSummary> list(String query) {
        String q = query == null ? "" : query.trim();
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql(SUMMARY_SQL + """
                        WHERE (:q = '' OR u.username ILIKE :pat OR p.email ILIKE :pat)
                        ORDER BY u.username LIMIT 200
                        """)
                .param("q", q).param("pat", pattern).query(UserAdminService::summary).list();
    }

    public UserDetail detail(UUID userId) {
        UserSummary user = jdbc.sql(SUMMARY_SQL + " WHERE u.id = :id").param("id", userId)
                .query(UserAdminService::summary).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_unknown"));
        return new UserDetail(user, roleRepository.rolesOf(userId), permissionRepository.userValues(userId),
                permissions.effective(userId));
    }

    @Transactional
    public void setDirectPermission(UUID actor, UUID userId, String key, PermissionValue value, String ip) {
        requireUser(userId);
        permissions.setUserValue(userId, key, value);
        audit.record(actor, userId, "permission.set", Map.of("permission", key), ip);
    }

    @Transactional
    public void removeDirectPermission(UUID actor, UUID userId, String key, String ip) {
        requireUser(userId);
        permissionRepository.removeUserValue(userId, key);
        audit.record(actor, userId, "permission.removed", Map.of("permission", key), ip);
    }

    /** 2FA und Wiederherstellungscodes entfernen, alle Sitzungen und Tokens beenden. Danach reicht das Passwort. */
    @Transactional
    public void reset2fa(UUID actor, UUID userId, String ip) {
        requireUser(userId);
        jdbc.sql("DELETE FROM user_totp WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM totp_enrollments WHERE user_id = :u").param("u", userId).update();
        int ended = sessions.revokeAll(userId);
        int revoked = tokens.revokeAll(userId);
        audit.record(actor, userId, "admin.reset_2fa", Map.of("sessions_ended", ended, "authorizations_revoked", revoked), ip);
    }

    private void requireUser(UUID userId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id)").param("id", userId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_unknown");
        }
    }

    private static final String SUMMARY_SQL = """
            SELECT u.id, u.username, p.email, u.language, u.banned, u.created_at,
                   EXISTS (SELECT 1 FROM user_totp t WHERE t.user_id = u.id) AS has_totp,
                   COALESCE((SELECT string_agg(r.name, ', ' ORDER BY r.name) FROM user_roles ur
                             JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = u.id), '') AS roles
            FROM users u LEFT JOIN user_pii p ON p.user_id = u.id
            """;

    private static UserSummary summary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserSummary(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("language"), rs.getBoolean("banned"), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("has_totp"), rs.getString("roles"));
    }
}
