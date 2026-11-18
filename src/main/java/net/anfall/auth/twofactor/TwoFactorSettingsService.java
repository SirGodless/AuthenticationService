package net.anfall.auth.twofactor;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.password.PasswordService;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2FA selbst verwalten (Profil): einrichten, Wiederherstellungscodes neu erzeugen, abschalten.
 *
 * Schutz gegen übernommene Sitzungen: Jede dieser Aktionen verlangt das Passwort, die mit bestehender 2FA zusätzlich
 * einen aktuellen Code. Falsche Eingaben zählen für das Limit (5 in 15 Minuten, dann Pause).
 * Für User mit einem Verwaltungsrecht (admin.*) ist die 2FA Pflicht und lässt sich nicht abschalten.
 */
@Service
public class TwoFactorSettingsService {

    /** Zustand für die Seite. mandatory: Die 2FA ist Pflicht, weil der User ein Verwaltungsrecht hat. */
    public record Status(boolean enabled, long recoveryCodesLeft, boolean mandatory) {
    }

    /** Das Geheimnis für die Authenticator-App. */
    public record Enrollment(String secret, String issuer) {
    }

    private static final String LIMIT = "reauth";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final PasswordService passwords;
    private final AccountRepository accounts;
    private final SecondFactorChecker checker;
    private final PermissionService permissions;
    private final SessionService sessions;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AppProperties props;

    public TwoFactorSettingsService(JdbcClient jdbc, SecretCipher cipher, PasswordService passwords,
                                    AccountRepository accounts, SecondFactorChecker checker,
                                    PermissionService permissions, SessionService sessions, Throttle throttle,
                                    AuditLog audit, AppProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.passwords = passwords;
        this.accounts = accounts;
        this.checker = checker;
        this.permissions = permissions;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.props = props;
    }

    public Status status(UUID userId) {
        long left = jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u AND used_at IS NULL")
                .param("u", userId).query(Long.class).single();
        return new Status(hasTotp(userId), left, permissions.hasAnyAdminPermission(userId));
    }

    /** Schritt 1: Passwort bestätigen, Geheimnis erzeugen. Die 2FA gilt erst nach Schritt 2. */
    @Transactional
    public Enrollment startEnrollment(UUID userId, String password, String ip) {
        verifyPassword(userId, password, ip);
        if (hasTotp(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "two_factor_already_enabled");
        }
        byte[] secret = Totp.newSecret();
        jdbc.sql("""
                        INSERT INTO totp_enrollments (user_id, secret_enc) VALUES (:u, :s)
                        ON CONFLICT (user_id) DO UPDATE SET secret_enc = :s, created_at = now()
                        """).param("u", userId).param("s", cipher.encrypt(secret)).update();
        return new Enrollment(Totp.toBase32(secret), props.totpIssuer());
    }

    /** Schritt 2: Code aus der App bestätigen. Erst jetzt wird die 2FA angelegt. Gibt die neuen Codes zurück. */
    @Transactional
    public List<String> confirmEnrollment(UUID userId, String code, String ip) {
        failIfLocked(userId);
        byte[] enc = jdbc.sql("""
                        SELECT secret_enc FROM totp_enrollments
                        WHERE user_id = :u AND created_at > now() - interval '15 minutes'
                        """).param("u", userId).query(byte[].class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "totp_not_prepared"));
        OptionalLong step = Totp.verify(cipher.decrypt(enc), code == null ? "" : code.trim(), System.currentTimeMillis(), null);
        if (step.isEmpty()) {
            countFailure(userId, ip, "confirm");
            throw new ApiException(HttpStatus.BAD_REQUEST, "totp_code_invalid");
        }
        throttle.clear(LIMIT, userId.toString());
        jdbc.sql("INSERT INTO user_totp (user_id, secret_enc, last_used_step) VALUES (:u, :s, :step)")
                .param("u", userId).param("s", enc).param("step", step.getAsLong()).update();
        jdbc.sql("DELETE FROM totp_enrollments WHERE user_id = :u").param("u", userId).update();
        List<String> codes = replaceRecoveryCodes(userId);
        audit.record(userId, userId, "2fa.enabled", Map.of(), ip);
        return codes;
    }

    /** Neue Wiederherstellungscodes. Die alten gelten danach nicht mehr. Verlangt Passwort und aktuellen Code. */
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID userId, String password, String code, String ip) {
        verifyPasswordAndCode(userId, password, code, ip);
        List<String> codes = replaceRecoveryCodes(userId);
        audit.record(userId, userId, "2fa.recovery_codes_regenerated", Map.of(), ip);
        return codes;
    }

    /** Schaltet die 2FA ab (nur für User ohne Verwaltungsrecht). Andere Sitzungen enden. */
    @Transactional
    public void disable(UUID userId, String currentSessionToken, String password, String code, String ip) {
        verifyPasswordAndCode(userId, password, code, ip);
        if (permissions.hasAnyAdminPermission(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "two_factor_required_for_admins");
        }
        jdbc.sql("DELETE FROM user_totp WHERE user_id = :u").param("u", userId).update();
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
        int ended = sessions.revokeAllExcept(userId, currentSessionToken);
        audit.record(userId, userId, "2fa.disabled", Map.of("other_sessions_ended", ended), ip);
    }

    // ---- intern ----

    private void verifyPasswordAndCode(UUID userId, String password, String code, String ip) {
        verifyPassword(userId, password, ip);
        if (!hasTotp(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "two_factor_not_enabled");
        }
        String clean = code == null ? "" : code.trim();
        boolean ok = SecondFactorChecker.looksLikeTotpCode(clean) ? checker.verifyTotp(userId, clean)
                : checker.useRecoveryCode(userId, clean);
        if (!ok) {
            countFailure(userId, ip, "code");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_code");
        }
        throttle.clear(LIMIT, userId.toString());
    }

    private void verifyPassword(UUID userId, String password, String ip) {
        failIfLocked(userId);
        if (!passwords.matches(password, accounts.passwordHash(userId))) {
            countFailure(userId, ip, "password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials");
        }
    }

    private void failIfLocked(UUID userId) {
        if (throttle.isLocked(LIMIT, userId.toString())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts");
        }
    }

    private void countFailure(UUID userId, String ip, String what) {
        var l = props.limits();
        throttle.fail(LIMIT, userId.toString(), l.loginMaxFailures(), l.loginWindow(), l.loginLock());
        audit.recordNow(userId, userId, "2fa.settings_failed", Map.of("step", what), ip);
    }

    private boolean hasTotp(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_totp WHERE user_id = :u)").param("u", userId)
                .query(Boolean.class).single();
    }

    private List<String> replaceRecoveryCodes(UUID userId) {
        jdbc.sql("DELETE FROM recovery_codes WHERE user_id = :u").param("u", userId).update();
        List<String> codes = RecoveryCodes.generate();
        for (String c : codes) {
            jdbc.sql("INSERT INTO recovery_codes (id, user_id, code_hash) VALUES (:id, :u, :h)")
                    .param("id", UUID.randomUUID()).param("u", userId).param("h", RecoveryCodes.hash(c)).update();
        }
        return codes;
    }
}
