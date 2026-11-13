package net.anfall.auth.auth;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.RecoveryCodes;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.crypto.Totp;
import net.anfall.auth.security.SessionService;
import net.anfall.auth.user.AccountFields;
import net.anfall.auth.user.AccountService;
import net.anfall.auth.user.PasswordService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Anmelden mit Username oder E-Mail, Passwort und (falls eingerichtet) 2FA-Code oder Wiederherstellungscode.
 *
 * Absichtlich NICHT @Transactional: Fehlversuche und Protokolleinträge müssen erhalten bleiben,
 * auch wenn die Anfrage danach mit einem Fehler endet.
 */
@Service
public class LoginService {

    /** Ergebnis eines Anmeldeschritts: Cookie für die Antwort und ob noch ein 2FA-Code fehlt. */
    public record LoginResult(boolean totpRequired, ResponseCookie cookie) {
    }

    private final AccountService accounts;
    private final PasswordService passwords;
    private final SessionService sessions;
    private final Throttle throttle;
    private final AuditLog audit;
    private final SecretCipher cipher;
    private final JdbcClient jdbc;
    private final AppProperties.Limits limits;

    public LoginService(AccountService accounts, PasswordService passwords, SessionService sessions,
                        Throttle throttle, AuditLog audit, SecretCipher cipher, JdbcClient jdbc,
                        AppProperties props) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.limits = props.limits();
    }

    /** Schritt 1: Kennung und Passwort. */
    public LoginResult login(String identifier, String password, boolean remember, String ip) {
        String id = AccountFields.normalizeEmail(identifier); // klein geschrieben, gilt für Username und E-Mail
        var account = accounts.findForLogin(id);

        // Pro Konto gesperrt, bei unbekannten Kennungen pro Kennung: So verrät die Sperre nicht, ob es das Konto gibt.
        String lockKey = account.map(a -> a.id().toString()).orElse("unknown:" + id);
        if (throttle.isLocked("login", lockKey)) {
            audit.record(null, account.map(AccountService.LoginData::id).orElse(null), "login.locked", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }

        boolean ok;
        if (account.isPresent()) {
            ok = passwords.matches(password, account.get().passwordHash());
        } else {
            passwords.dummyMatch(password); // gleich lange Rechenzeit wie bei einem echten Konto
            ok = false;
        }
        if (!ok) {
            throttle.fail("login", lockKey, limits.loginMaxFailures(), limits.loginWindow(), limits.loginLock());
            // Die eingegebene Kennung kommt nicht ins Protokoll (kann aus Versehen ein Passwort sein)
            audit.record(null, account.map(AccountService.LoginData::id).orElse(null), "login.failed",
                    Map.of("reason", "bad_credentials"), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
        var a = account.get();
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
            SessionService.Created pending = sessions.create(a.id(), remember, true);
            return new LoginResult(true, pending.cookie());
        }
        SessionService.Created session = sessions.create(a.id(), remember, false);
        audit.record(a.id(), a.id(), "login.success", Map.of("remember", remember, "second_factor", "none"), ip);
        return new LoginResult(false, session.cookie());
    }

    /** Schritt 2: TOTP-Code (6 Ziffern) oder Wiederherstellungscode. */
    public LoginResult secondFactor(String pendingToken, String code, String ip) {
        var pending = sessions.find(pendingToken).filter(SessionService.SessionInfo::pending)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "no_pending_login"));
        UUID userId = pending.userId();
        String lockKey = userId.toString();

        if (throttle.isLocked("totp", lockKey)) {
            audit.record(null, userId, "login.locked", Map.of("step", "second_factor"), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }

        String clean = code == null ? "" : code.trim();
        boolean viaRecovery = !clean.matches("\\d{6}");
        boolean ok = viaRecovery ? useRecoveryCode(userId, clean) : verifyTotp(userId, clean);
        if (!ok) {
            throttle.fail("totp", lockKey, limits.loginMaxFailures(), limits.loginWindow(), limits.loginLock());
            audit.record(null, userId, "login.failed", Map.of("reason", "bad_second_factor"), ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_code");
        }
        throttle.clear("totp", lockKey);

        // Neue Sitzung statt die wartende zu befördern (gegen Session Fixation)
        sessions.revoke(pendingToken);
        SessionService.Created session = sessions.create(userId, pending.remember(), false);
        audit.record(userId, userId, "login.success",
                Map.of("remember", pending.remember(), "second_factor", viaRecovery ? "recovery_code" : "totp"), ip);
        return new LoginResult(false, session.cookie());
    }

    public void logout(String token, UUID userId, String ip) {
        sessions.revoke(token);
        audit.record(userId, userId, "logout", Map.of(), ip);
    }

    private boolean verifyTotp(UUID userId, String code) {
        byte[] enc = jdbc.sql("SELECT secret_enc FROM user_totp WHERE user_id = :u").param("u", userId)
                .query(byte[].class).optional().orElse(null);
        if (enc == null) {
            return false;
        }
        Long lastUsed = jdbc.sql("SELECT last_used_step FROM user_totp WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> rs.getObject("last_used_step", Long.class)).optional().orElse(null);
        OptionalLong step = Totp.verify(cipher.decrypt(enc), code, System.currentTimeMillis(), lastUsed);
        if (step.isEmpty()) {
            return false;
        }
        // Bedingtes UPDATE: Zwei gleichzeitige Anfragen mit demselben Code, nur eine gewinnt (kein Wiederverwenden).
        return jdbc.sql("""
                        UPDATE user_totp SET last_used_step = :s
                        WHERE user_id = :u AND (last_used_step IS NULL OR last_used_step < :s)
                        """)
                .param("s", step.getAsLong()).param("u", userId).update() == 1;
    }

    private boolean useRecoveryCode(UUID userId, String code) {
        return jdbc.sql("""
                        UPDATE recovery_codes SET used_at = now()
                        WHERE user_id = :u AND code_hash = :h AND used_at IS NULL
                        """)
                .param("u", userId).param("h", RecoveryCodes.hash(code)).update() == 1;
    }
}
