package net.anfall.auth.useradmin;

import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.emailtoken.EmailTokens;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.mail.MailService;
import net.anfall.auth.oidc.TokenRevocationService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Passwort-Reset erzwingen (NIST SP 800-63B-4: "verifiers SHALL force a change if there is evidence that the authenticator
 * has been compromised"). Das alte Passwort wird sofort ungültig, alle Sitzungen und Tokens enden, der User bekommt
 * einen Reset-Link per Mail. Ist der Link abgelaufen, kann er "Passwort vergessen" nutzen.
 */
@Service
public class ForcedPasswordResetService {

    /** Kein gültiger Hash: Passwort-Vergleiche schlagen immer fehl, bis ein neues Passwort gesetzt ist. */
    private static final String LOCKED_HASH = "!forced-reset";

    private final AccountRepository accounts;
    private final SessionService sessions;
    private final TokenRevocationService tokens;
    private final EmailTokens emailTokens;
    private final MailService mail;
    private final AuditLog audit;
    private final AppProperties props;

    public ForcedPasswordResetService(AccountRepository accounts, SessionService sessions, TokenRevocationService tokens,
                                      EmailTokens emailTokens, MailService mail, AuditLog audit, AppProperties props) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokens = tokens;
        this.emailTokens = emailTokens;
        this.mail = mail;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public void force(UUID actor, UUID targetId, String ip) {
        var target = accounts.findContactById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_unknown"));
        // Ohne bestätigte Adresse gäbe es keinen Weg zurück ins Konto
        if (!target.emailVerified()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "email_not_verified");
        }
        accounts.setPasswordHash(targetId, LOCKED_HASH);
        int endedSessions = sessions.revokeAll(targetId);
        int revoked = tokens.revokeAll(targetId);
        String token = emailTokens.issue(targetId, EmailTokens.Purpose.PASSWORD_RESET, props.tokens().passwordResetTtl());
        mail.sendForcedPasswordReset(target.email(), target.language(), target.username(), token);
        audit.record(actor, targetId, "password.reset_forced",
                Map.of("sessions_ended", endedSessions, "authorizations_revoked", revoked), ip);
    }
}
