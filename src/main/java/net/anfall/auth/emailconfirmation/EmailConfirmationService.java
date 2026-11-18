package net.anfall.auth.emailconfirmation;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountInputRules;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.emailtoken.EmailTokens;
import net.anfall.auth.mail.MailService;
import net.anfall.auth.ratelimit.Throttle;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bestätigung der E-Mail-Adresse. Ohne Bestätigung kein Login. */
@Service
public class EmailConfirmationService {

    private final EmailTokens tokens;
    private final MailService mail;
    private final Throttle throttle;
    private final AuditLog audit;
    private final AccountRepository accounts;
    private final AppProperties props;

    public EmailConfirmationService(EmailTokens tokens, MailService mail, Throttle throttle, AuditLog audit,
                                    AccountRepository accounts, AppProperties props) {
        this.tokens = tokens;
        this.mail = mail;
        this.throttle = throttle;
        this.audit = audit;
        this.accounts = accounts;
        this.props = props;
    }

    /** Schickt die Bestätigungsmail für ein neues Konto (Aufruf aus der Registrierung, Etappe 5). */
    @Transactional
    public void sendFor(UUID userId, String email, String language, String username) {
        String token = tokens.issue(userId, EmailTokens.Purpose.CONFIRM_EMAIL, props.tokens().confirmEmailTtl());
        mail.sendConfirmEmail(email, language, username, token);
    }

    /** Prüft nur, ob der Link gültig ist (für die Seite). Verbraucht ihn nicht. */
    public boolean isValid(String token) {
        return tokens.isValid(token, EmailTokens.Purpose.CONFIRM_EMAIL);
    }

    /** Verbraucht den Link (beim Klick auf den Knopf, nicht beim bloßen Öffnen) und bestätigt die Adresse. */
    @Transactional
    public void confirm(String token, String ip) {
        UUID userId = tokens.consume(token, EmailTokens.Purpose.CONFIRM_EMAIL)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "invalid_link"));
        accounts.markEmailVerified(userId);
        audit.record(userId, userId, "email.confirmed", Map.of(), ip);
    }

    /**
     * Bestätigungsmail erneut senden. Antwort ist immer gleich, egal ob es die Adresse gibt.
     * Limit: je E-Mail und je IP getrennt gezählt, dann 429 (verrät nichts, weil auch unbekannte Adressen zählen).
     */
    @Transactional
    public void resend(String email, String ip) {
        String normalized = AccountInputRules.normalizeEmail(email);
        var limits = props.limits();
        boolean byEmail = throttle.allow("resend:email", normalized, limits.mailRequestsMax(),
                limits.mailRequestsWindow());
        boolean byIp = throttle.allow("resend:ip", ClientIp.rateKey(ip), limits.mailRequestsMax(),
                limits.mailRequestsWindow());
        if (!byEmail || !byIp) {
            audit.recordNow(null, null, "email.resend_limited", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
        }
        accounts.findContactByEmail(normalized).filter(c -> !c.emailVerified() && !c.banned()).ifPresent(c -> {
            String token = tokens.issue(c.id(), EmailTokens.Purpose.CONFIRM_EMAIL, props.tokens().confirmEmailTtl());
            mail.sendConfirmEmail(c.email(), c.language(), c.username(), token);
            audit.record(c.id(), c.id(), "email.confirmation_sent", Map.of("reason", "resend"), ip);
        });
    }
}
