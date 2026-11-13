package net.anfall.auth.password;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountContact;
import net.anfall.auth.account.AccountInputRules;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.account.AccountService;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.emailtoken.EmailTokens;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.mail.MailService;
import net.anfall.auth.password.check.PasswordContext;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Passwort vergessen: Reset-Mail anfordern und mit dem Link ein neues Passwort setzen. */
@Service
public class PasswordResetService {

    private final EmailTokens tokens;
    private final MailService mail;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AppProperties props;
    private final PasswordService passwords;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final SessionService sessions;

    public PasswordResetService(EmailTokens tokens, MailService mail, Throttle throttle, AuditLog audit,
                                AppProperties props, PasswordService passwords, AccountRepository accounts,
                                AccountService accountService, SessionService sessions) {
        this.tokens = tokens;
        this.mail = mail;
        this.throttle = throttle;
        this.audit = audit;
        this.props = props;
        this.passwords = passwords;
        this.accounts = accounts;
        this.accountService = accountService;
        this.sessions = sessions;
    }

    /**
     * Reset-Mail anfordern. Die Antwort ist neutral: Sie verrät nicht, ob die Adresse existiert.
     * Eine Mail geht nur an bestätigte Adressen von nicht gesperrten Konten. Limit: 3 pro Stunde
     * je E-Mail und je IP (getrennt gezählt, auch für unbekannte Adressen, daher verrät das 429 nichts).
     */
    @Transactional
    public void request(String email, String ip) {
        String normalized = AccountInputRules.normalizeEmail(email);
        var limits = props.limits();
        boolean byEmail = throttle.allow("reset:email", normalized, limits.mailRequestsMax(),
                limits.mailRequestsWindow());
        boolean byIp = throttle.allow("reset:ip", ClientIp.rateKey(ip), limits.mailRequestsMax(),
                limits.mailRequestsWindow());
        if (!byEmail || !byIp) {
            audit.recordNow(null, null, "password_reset.limited", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
        }
        accounts.findContactByEmail(normalized).filter(c -> c.emailVerified() && !c.banned()).ifPresent(c -> {
            String token = tokens.issue(c.id(), EmailTokens.Purpose.PASSWORD_RESET, props.tokens().passwordResetTtl());
            mail.sendPasswordReset(c.email(), c.language(), c.username(), token);
            audit.record(c.id(), c.id(), "password_reset.requested", Map.of(), ip);
        });
    }

    /** Prüft nur, ob der Link gültig ist (für die Seite). Verbraucht ihn nicht. */
    public boolean isValid(String token) {
        return tokens.isValid(token, EmailTokens.Purpose.PASSWORD_RESET);
    }

    /**
     * Neues Passwort setzen. Das Passwort wird zuerst geprüft, damit ein zu schwaches Passwort den Link
     * nicht verbraucht. Danach enden alle Sitzungen. Die 2FA bleibt unverändert.
     */
    @Transactional
    public void confirm(String token, String newPassword, String ip) {
        // Ungültiger Link: immer die neutrale Meldung, nie ein Passwortfehler
        UUID userId = tokens.peekUser(token, EmailTokens.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "invalid_link"));
        AccountContact account = accounts.findContactById(userId).orElseThrow();
        String error = passwords.check(newPassword, PasswordContext.of(account.username(), account.email()));
        if (error != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        }
        tokens.consume(token, EmailTokens.Purpose.PASSWORD_RESET)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "invalid_link"));
        accountService.changePassword(userId, newPassword);
        int ended = sessions.revokeAll(userId);
        audit.record(userId, userId, "password.reset", Map.of("sessions_ended", ended), ip);
    }
}
