package net.anfall.auth.oidc;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.anfall.auth.login.session.SessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Wie die Anmeldung für die API, aber mit einem Ergebnis, das Spring Authorization Server in seiner
 * Datenbank ablegen kann: Der Principal ist ein normaler Spring-User, sein Name ist die User-UUID (daraus wird `sub`).
 *
 * Ohne "Eingeloggt bleiben" trägt die Anmeldung zusätzlich das Ende der Sitzung (SSO_END_&lt;Sekunden&gt;). Der Token-Check
 * lässt Refresh-Tokens dann nach diesem Zeitpunkt nicht mehr zu (Abschnitt 3: "sonst endet die Sitzung nach 12 Stunden").
 */
@Component
public class OidcSecurityContextRepository implements SecurityContextRepository {

    static final String SSO_END_PREFIX = "SSO_END_";

    private final SessionService sessions;

    public OidcSecurityContextRepository(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        SecurityContext context = new SecurityContextImpl();
        sessions.find(SessionService.tokenFrom(holder.getRequest())).filter(s -> !s.pending()).ifPresent(info -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            // Daraus macht der Provider den Claim auth_time (Zeitpunkt der Anmeldung) im ID-Token
            authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY)
                    .issuedAt(info.createdAt()).build());
            if (!info.remember()) {
                authorities.add(new SimpleGrantedAuthority(SSO_END_PREFIX + info.expiresAt().getEpochSecond()));
            }
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new User(info.userId().toString(), "", authorities), null, authorities));
        });
        return context;
    }

    /** Ende der Sitzung aus den Rechten lesen. Leer bei "Eingeloggt bleiben". */
    static java.util.Optional<Instant> ssoEnd(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).filter(a -> a.startsWith(SSO_END_PREFIX))
                .map(a -> Instant.ofEpochSecond(Long.parseLong(a.substring(SSO_END_PREFIX.length())))).findFirst();
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // bewusst leer, Sitzungen entstehen nur im LoginService
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return SessionService.tokenFrom(request) != null;
    }
}
