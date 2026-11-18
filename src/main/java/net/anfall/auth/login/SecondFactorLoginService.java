package net.anfall.auth.login;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.twofactor.SecondFactorChecker;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Zweiter Schritt der Anmeldung: TOTP-Code oder Wiederherstellungscode. Erst hier wird aus der wartenden Sitzung
 * (nur Passwort stimmt) eine echte Anmeldung. Nicht @Transactional, aus demselben Grund wie {@link LoginService}.
 */
@Service
public class SecondFactorLoginService {

    private final SessionService sessions;
    private final SecondFactorChecker checker;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AppProperties.Limits limits;

    public SecondFactorLoginService(SessionService sessions, SecondFactorChecker checker, Throttle throttle,
                                    AuditLog audit, AppProperties props) {
        this.sessions = sessions;
        this.checker = checker;
        this.throttle = throttle;
        this.audit = audit;
        this.limits = props.limits();
    }

    public LoginResult verify(String pendingToken, String code, String ip) {
        var pending = sessions.find(pendingToken).filter(SessionService.SessionInfo::pending)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "no_pending_login"));
        UUID userId = pending.userId();
        String lockKey = userId.toString();

        if (throttle.isLocked("totp", lockKey)) {
            audit.record(null, userId, "login.locked", Map.of("step", "second_factor"), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }

        String clean = code == null ? "" : code.trim();
        boolean viaRecovery = !SecondFactorChecker.looksLikeTotpCode(clean);
        boolean ok = viaRecovery ? checker.useRecoveryCode(userId, clean) : checker.verifyTotp(userId, clean);
        if (!ok) {
            throttle.fail("totp", lockKey, limits.loginMaxFailures(), limits.loginWindow(), limits.loginLock());
            audit.record(null, userId, "login.failed", Map.of("reason", "bad_second_factor"), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_code");
        }
        throttle.clear("totp", lockKey);

        // Neue Sitzung statt die wartende zu befördern (gegen Session Fixation)
        sessions.revoke(pendingToken);
        var session = sessions.create(userId, pending.remember(), false);
        audit.record(userId, userId, "login.success",
                Map.of("remember", pending.remember(), "second_factor", viaRecovery ? "recovery_code" : "totp"), ip);
        return new LoginResult(false, session.cookie());
    }
}
