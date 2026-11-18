package net.anfall.auth.oidc;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.service.ServiceInfo;
import net.anfall.auth.service.ServiceRepository;
import net.anfall.auth.web.ClientIp;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Der Zugang wird hier entschieden, am Authorize-Endpunkt (Abschnitt 1 der Spezifikation): Fehlt dem angemeldeten User
 * das Recht des Dienstes, gibt der Provider keinen Code aus. Statt den Browser mit einem Fehler an den Dienst
 * zurückzuschicken, zeigt er eine verständliche Seite in der Sprache des Users (/access-denied).
 * Ein Client ohne Dienst wird abgelehnt: Jeder Client muss ein Recht verlangen.
 */
@Component
class ServicePermissionFilter extends OncePerRequestFilter {

    private final RegisteredClientRepository clients;
    private final ServiceRepository services;
    private final PermissionService permissions;
    private final AuditLog audit;
    private final ClientIp clientIp;

    ServicePermissionFilter(RegisteredClientRepository clients, ServiceRepository services,
                            PermissionService permissions, AuditLog audit, ClientIp clientIp) {
        this.clients = clients;
        this.services = services;
        this.permissions = permissions;
        this.audit = audit;
        this.clientIp = clientIp;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LoginRedirectEntryPoint.AUTHORIZE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        String clientId = request.getParameter("client_id");
        // Nicht angemeldet oder unbekannter Client: den Rest erledigt Spring (Login-Weiterleitung, Fehlerseite)
        if (user == null || user instanceof AnonymousAuthenticationToken || !user.isAuthenticated()
                || clientId == null || clients.findByClientId(clientId) == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId = UUID.fromString(user.getName());
        ServiceInfo service = services.findByClientId(clientId).orElse(null);
        if (service != null && permissions.has(userId, service.requiredPermission())) {
            chain.doFilter(request, response);
            return;
        }
        audit.recordNow(userId, userId, "oidc.access_denied",
                Map.of("service", service == null ? "(kein Dienst)" : service.key()), clientIp.of(request));
        String name = service == null ? clientId : service.displayName();
        response.sendRedirect("/access-denied?service=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
    }
}
