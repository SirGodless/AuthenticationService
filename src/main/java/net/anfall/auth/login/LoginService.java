package net.anfall.auth.login;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountInputRules;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.account.LoginAccount;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.password.PasswordService;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Erster Schritt der Anmeldung (Username oder E-Mail plus Passwort) und Abmelden.
 * Den zweiten Faktor prüft {@link SecondFactorLoginService}.
 *
 * Absichtlich NICHT @Transactional: Fehlversuche und Protokolleinträge müssen erhalten bleiben,
 * auch wenn die Anfrage danach mit einem Fehler endet.
 */
@Service
public class LoginService {

    private final AccountRepository accounts;
    private final PasswordService passwords;
    private final SessionService sessions;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AppProperties.Limits limits;

    public LoginService(AccountRepository accounts, PasswordService passwords, SessionService sessions,
                        Throttle throttle, AuditLog audit, AppProperties props) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.limits = props.limits();
    }

    public LoginResult login(String identifier, String password, boolean remember, String ip) {
        String id = AccountInputRules.normalizeEmail(identifier); // klein geschrieben, gilt für Username und E-Mail
        var account = accounts.findForLogin(id);

        // Pro Konto gesperrt, bei unbekannten Kennungen pro Kennung: So verrät die Sperre nicht, ob es das Konto gibt.
        String lockKey = account.map(a -> a.id().toString()).orElse("unknown:" + id);
        if (throttle.isLocked("login", lockKey)) {
            audit.record(null, account.map(LoginAccount::id).orElse(null), "login.locked", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }

        boolean passwordOk;
        if (account.isPresent()) {
            passwordOk = passwords.matches(password, account.get().passwordHash());
        } else {
            passwords.dummyMatch(password); // gleich lange Rechenzeit wie bei einem echten Konto
            passwordOk = false;
        }
        if (!passwordOk) {
            throttle.fail("login", lockKey, limits.loginMaxFailures(), limits.loginWindow(), limits.loginLock());
            // Die eingegebene Kennung kommt nicht ins Protokoll (kann aus Versehen ein Passwort sein)
            audit.record(null, account.map(LoginAccount::id).orElse(null), "login.failed",
                    Map.of("reason", "bad_credentials"), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        LoginAccount a = account.get();
        throttle.clear("login", lockKey);

        // Erst nach richtigem Passwort verraten wir den Grund, vorher wäre es eine Auskunft über das Konto.
        if (a.banned()) {
            audit.record(a.id(), a.id(), "login.denied", Map.of("reason", "banned"), ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "account_banned");
        }
        if (!a.emailVerified()) {
            audit.record(a.id(), a.id(), "login.denied", Map.of("reason", "email_not_verified"), ip);
            throw new ApiException(HttpStatus.FORBIDDEN, "email_not_verified");
        }

        if (a.hasTotp()) {
            return new LoginResult(true, sessions.create(a.id(), remember, true).cookie());
        }
        var session = sessions.create(a.id(), remember, false);
        audit.record(a.id(), a.id(), "login.success", Map.of("remember", remember, "second_factor", "none"), ip);
        return new LoginResult(false, session.cookie());
    }

    public void logout(String token, UUID userId, String ip) {
        sessions.revoke(token);
        audit.record(userId, userId, "logout", Map.of(), ip);
    }
}
