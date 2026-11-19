package net.anfall.auth.oidc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountContact;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.service.ServiceInfo;
import net.anfall.auth.service.ServiceRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Greift bei jedem ausgestellten Token, also beim Tausch des Codes und bei jedem Refresh:
 * 1. Zugang prüfen: User nicht gebannt, Dienst vorhanden, Recht des Dienstes vorhanden, Sitzung (ohne "Eingeloggt
 *    bleiben") nicht länger als 12 Stunden her. Sonst wird die Autorisierung gelöscht und der Refresh-Token ist tot.
 *    Damit wirken Entzug des Rechts und Bann beim nächsten Refresh (Abschnitt 6 der Spezifikation).
 * 2. Claims in das ID-Token schreiben: email, email_verified, preferred_username (je nach Scope).
 */
@Component
class OidcTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    /** Claim, der Dienst-Tokens von User-Tokens unterscheidet. */
    static final String TOKEN_USE = "token_use";
    static final String SERVICE_TOKEN = "service";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String ID_TOKEN = "id_token";

    private final AccountRepository accounts;
    private final ServiceRepository services;
    private final PermissionService permissions;
    private final OAuth2AuthorizationService authorizations;
    private final AuditLog audit;

    OidcTokenCustomizer(AccountRepository accounts, ServiceRepository services, PermissionService permissions,
                        OAuth2AuthorizationService authorizations, AuditLog audit) {
        this.accounts = accounts;
        this.services = services;
        this.permissions = permissions;
        this.authorizations = authorizations;
        this.audit = audit;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        String tokenType = context.getTokenType().getValue();
        // Token eines Dienstes für die Dienst-Schnittstelle (client_credentials): kein User dahinter, nur markieren.
        // Das Merkmal gibt es in keinem User-Token, so lässt sich ein User-Token nie als Dienst-Token benutzen.
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            if (ACCESS_TOKEN.equals(tokenType)) {
                context.getClaims().claim(TOKEN_USE, SERVICE_TOKEN);
            }
            return;
        }
        Authentication principal = context.getPrincipal();
        UUID userId = UUID.fromString(principal.getName());
        AccountContact account = accounts.findContactById(userId).orElse(null);

        if (ACCESS_TOKEN.equals(tokenType)) {
            ServiceInfo service = checkAccess(context, userId, account, principal);
            if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
                audit.record(userId, userId, "oidc.login", Map.of("service", service.key()), null);
            }
        } else if (ID_TOKEN.equals(tokenType) && account != null) {
            // Kennung der Hub-Sitzung: Damit weiß der Dienst, welche seiner Sitzungen beim Abmelden endet
            OidcSecurityContextRepository.sid(principal.getAuthorities()).ifPresent(sid -> context.getClaims().claim("sid", sid));
            if (context.getAuthorizedScopes().contains(OidcScopes.PROFILE)) {
                context.getClaims().claim("preferred_username", account.username());
                context.getClaims().claim("locale", account.language());
            }
            if (context.getAuthorizedScopes().contains(OidcScopes.EMAIL)) {
                context.getClaims().claim("email", account.email());
                // Nur bestätigte Adressen können sich anmelden, trotzdem aus der Datenbank gelesen
                context.getClaims().claim("email_verified", account.emailVerified());
            }
        }
    }

    private ServiceInfo checkAccess(JwtEncodingContext context, UUID userId, AccountContact account,
                                    Authentication principal) {
        ServiceInfo service = services.findByClientId(context.getRegisteredClient().getClientId()).orElse(null);
        String reason = null;
        if (account == null || account.banned()) {
            reason = "banned";
        } else if (service == null || !permissions.has(userId, service.requiredPermission())) {
            reason = "permission_missing";
        } else if (OidcSecurityContextRepository.ssoEnd(principal.getAuthorities())
                .filter(end -> Instant.now().isAfter(end)).isPresent()) {
            reason = "session_ended";
        }
        if (reason == null) {
            return service;
        }
        OAuth2Authorization authorization = context.getAuthorization();
        if (authorization != null) {
            authorizations.remove(authorization);   // Der Refresh-Token ist damit endgültig ungültig
        }
        audit.recordNow(userId, userId, "oidc.token_denied",
                Map.of("service", service == null ? "(kein Dienst)" : service.key(), "reason", reason), null);
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                "Access to this service is no longer allowed.", null));
    }
}
