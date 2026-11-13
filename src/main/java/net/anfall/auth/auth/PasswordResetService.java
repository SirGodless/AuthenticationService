package net.anfall.auth.auth;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.mail.MailService;
import net.anfall.auth.security.SessionService;
import net.anfall.auth.user.AccountFields;
import net.anfall.auth.user.AccountService;
import net.anfall.auth.user.PasswordService;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Passwort vergessen (per Mail) und Passwort ändern (angemeldet). */
@Service
public class PasswordResetService {

    private final EmailTokens tokens;
    private final MailService mail;
    private final Throttle throttle;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final PasswordService passwords;
    private final AccountService accounts;
    private final SessionService sessions;
    private final TransactionTemplate tx;

    public PasswordResetService(EmailTokens tokens, MailService mail, Throttle throttle, AuditLog audit,
                                JdbcClient jdbc, AppProperties props, PasswordService passwords,
                                AccountService accounts, SessionService sessions, TransactionTemplate tx) {
        this.tokens = tokens;
        this.mail = mail;
        this.throttle = throttle;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
        this.passwords = passwords;
        this.accounts = accounts;
        this.sessions = sessions;
        this.tx = tx;
    }

    private record Target(UUID id, String username, String language) {
    }

    /**
     * Reset-Mail anfordern. Die Antwort ist neutral: Sie verrät nicht, ob die Adresse existiert.
     * Eine Mail geht nur an bestätigte Adressen von nicht gesperrten Konten. Limit: 3 pro Stunde
     * je E-Mail und je IP (getrennt gezählt, auch für unbekannte Adressen, daher verrät das 429 nichts).
     */
    @Transactional
    public void request(String email, String ip) {
        String normalized = AccountFields.normalizeEmail(email);
        var l = props.limits();
        boolean byEmail = throttle.allow("reset:email", normalized, l.mailRequestsMax(), l.mailRequestsWindow());
        boolean byIp = throttle.allow("reset:ip", ClientIp.rateKey(ip), l.mailRequestsMax(), l.mailRequestsWindow());
        if (!byEmail || !byIp) {
            audit.recordNow(null, null, "password_reset.limited", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
        }
        Optional<Target> target = jdbc.sql("""
                        SELECT u.id, u.username, u.language FROM users u JOIN user_pii p ON p.user_id = u.id
                        WHERE p.email = :e AND p.email_verified AND NOT u.banned
                        """)
                .param("e", normalized)
                .query((rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("language")))
                .optional();
        if (target.isPresent()) {
            Target t = target.get();
            String token = tokens.issue(t.id(), EmailTokens.Purpose.PASSWORD_RESET, props.tokens().passwordResetTtl());
            mail.sendPasswordReset(normalized, t.language(), t.username(), token);
            audit.record(t.id(), t.id(), "password_reset.requested", Map.of(), ip);
        }
    }

    public boolean isValid(String token) {
        return tokens.isValid(token, EmailTokens.Purpose.PASSWORD_RESET);
    }

    /**
     * Neues Passwort setzen. Das Passwort wird zuerst geprüft, damit ein zu schwaches Passwort den Link
     * nicht verbraucht. Danach enden alle Sitzungen. Die 2FA bleibt unverändert.
     */
    @Transactional
    public void confirm(String token, String newPassword, String ip) {
        String error = passwords.check(newPassword);
        if (error != null) {
            // Gültigkeit mitprüfen, damit ein ungültiger Link nicht als "Passwort zu kurz" erscheint
            if (!tokens.isValid(token, EmailTokens.Purpose.PASSWORD_RESET)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        }
        UUID userId = tokens.consume(token, EmailTokens.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "invalid_link"));
        accounts.setPassword(userId, newPassword);
        int ended = sessions.revokeAll(userId);
        audit.record(userId, userId, "password.reset", Map.of("sessions_ended", ended), ip);
    }

    /** Passwort ändern, wenn angemeldet. Das aktuelle Passwort ist nötig. Andere Sitzungen enden. */
    public void change(UUID userId, String currentToken, String currentPassword, String newPassword, String ip) {
        String lockKey = userId.toString();
        var l = props.limits();
        if (throttle.isLocked("pwchange", lockKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }
        String hash = jdbc.sql("SELECT password_hash FROM user_credentials WHERE user_id = :u").param("u", userId)
                .query(String.class).single();
        if (!passwords.matches(currentPassword, hash)) {
            throttle.fail("pwchange", lockKey, l.loginMaxFailures(), l.loginWindow(), l.loginLock());
            audit.recordNow(userId, userId, "password.change_failed", Map.of(), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        String error = passwords.check(newPassword);
        if (error != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        }
        throttle.clear("pwchange", lockKey);
        // Neues Passwort, Sitzungen und Protokoll zusammen oder gar nicht
        tx.executeWithoutResult(status -> {
            accounts.setPassword(userId, newPassword);
            int ended = sessions.revokeAllExcept(userId, currentToken);
            audit.record(userId, userId, "password.changed", Map.of("other_sessions_ended", ended), ip);
        });
    }
}
