package net.anfall.auth.login.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Verbindet das Sitzungs-Cookie mit Spring Security: Pro Anfrage wird die Sitzung in der Datenbank
 * nachgeschlagen. Gespeichert wird nichts, Sitzungen entstehen nur im LoginService.
 * Sitzungen, in denen noch der 2FA-Code fehlt, gelten als nicht angemeldet.
 */
@Component
public class SessionSecurityContextRepository implements SecurityContextRepository {

    private final SessionService sessions;

    public SessionSecurityContextRepository(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return load(holder.getRequest());
    }

    private SecurityContext load(HttpServletRequest request) {
        SecurityContext context = new SecurityContextImpl();
        Optional<SessionService.SessionInfo> info = sessions.find(SessionService.tokenFrom(request));
        if (info.isPresent() && !info.get().pending()) {
            AccountPrincipal principal = new AccountPrincipal(info.get().userId(), info.get().username());
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }
        return context;
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // bewusst leer
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return SessionService.tokenFrom(request) != null;
    }
}
