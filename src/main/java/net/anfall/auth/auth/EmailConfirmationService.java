package net.anfall.auth.auth;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.mail.MailService;
import net.anfall.auth.user.AccountFields;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bestätigung der E-Mail-Adresse. Ohne Bestätigung kein Login. */
@Service
public class EmailConfirmationService {

    private final EmailTokens tokens;
    private final MailService mail;
    private final Throttle throttle;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public EmailConfirmationService(EmailTokens tokens, MailService mail, Throttle throttle, AuditLog audit,
                                    JdbcClient jdbc, AppProperties props) {
        this.tokens = tokens;
        this.mail = mail;
        this.throttle = throttle;
        this.audit = audit;
        this.jdbc = jdbc;
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
        jdbc.sql("UPDATE user_pii SET email_verified = true WHERE user_id = :u").param("u", userId).update();
        audit.record(userId, userId, "email.confirmed", Map.of(), ip);
    }

    /**
     * Bestätigungsmail erneut senden. Antwort ist immer gleich, egal ob es die Adresse gibt.
     * Limit: je E-Mail und je IP getrennt gezählt, dann 429 (verrät nichts, weil auch unbekannte Adressen zählen).
     */
    @Transactional
    public void resend(String email, String ip) {
        String normalized = AccountFields.normalizeEmail(email);
        var l = props.limits();
        boolean byEmail = throttle.allow("resend:email", normalized, l.mailRequestsMax(), l.mailRequestsWindow());
        boolean byIp = throttle.allow("resend:ip", ClientIp.rateKey(ip), l.mailRequestsMax(), l.mailRequestsWindow());
        if (!byEmail || !byIp) {
            audit.recordNow(null, null, "email.resend_limited", Map.of(), ip);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
        }
        Optional<Target> target = jdbc.sql("""
                        SELECT u.id, u.username, u.language FROM users u JOIN user_pii p ON p.user_id = u.id
                        WHERE p.email = :e AND NOT p.email_verified AND NOT u.banned
                        """)
                .param("e", normalized)
                .query((rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("language")))
                .optional();
        if (target.isPresent()) {
            Target t = target.get();
            String token = tokens.issue(t.id(), EmailTokens.Purpose.CONFIRM_EMAIL, props.tokens().confirmEmailTtl());
            mail.sendConfirmEmail(normalized, t.language(), t.username(), token);
            audit.record(t.id(), t.id(), "email.confirmation_sent", Map.of("reason", "resend"), ip);
        }
    }

    private record Target(UUID id, String username, String language) {
    }
}
