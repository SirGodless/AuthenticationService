package net.anfall.auth.password;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountContact;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.account.AccountService;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.password.check.PasswordContext;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Passwort ändern, wenn man angemeldet ist. Das aktuelle Passwort ist nötig, andere Sitzungen enden. */
@Service
public class PasswordChangeService {

    private final PasswordService passwords;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SessionService sessions;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AppProperties.Limits limits;
    private final TransactionTemplate tx;

    public PasswordChangeService(PasswordService passwords, AccountRepository accounts, AccountService accountService,
                                 SessionService sessions, Throttle throttle, AuditLog audit, AppProperties props,
                                 TransactionTemplate tx) {
        this.passwords = passwords;
        this.accounts = accounts;
        this.accountService = accountService;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.limits = props.limits();
        this.tx = tx;
    }

    public void change(UUID userId, String currentToken, String currentPassword, String newPassword, String ip) {
        String lockKey = userId.toString();
        if (throttle.isLocked("pwchange", lockKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }
        if (!passwords.matches(currentPassword, accounts.passwordHash(userId))) {
            throttle.fail("pwchange", lockKey, limits.loginMaxFailures(), limits.loginWindow(), limits.loginLock());
            audit.recordNow(userId, userId, "password.change_failed", Map.of(), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        AccountContact account = accounts.findContactById(userId).orElseThrow();
        String error = passwords.check(newPassword, PasswordContext.of(account.username(), account.email()));
        if (error != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        }
        throttle.clear("pwchange", lockKey);
        // Neues Passwort, Sitzungen und Protokoll zusammen oder gar nicht
        tx.executeWithoutResult(status -> {
            accountService.changePassword(userId, newPassword);
            int ended = sessions.revokeAllExcept(userId, currentToken);
            audit.record(userId, userId, "password.changed", Map.of("other_sessions_ended", ended), ip);
        });
    }
}
