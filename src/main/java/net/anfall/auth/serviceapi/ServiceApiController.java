package net.anfall.auth.serviceapi;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountContact;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.permission.PermissionDefinition;
import net.anfall.auth.permission.PermissionRepository;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.service.ServiceHealthService;
import net.anfall.auth.service.ServiceInfo;
import net.anfall.auth.service.ServiceRepository;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die Schnittstelle für Dienste (Beschreibung: docs/INTEGRATION.md und docs/api/openapi.yaml).
 * - GET /api/service/users/{sub}/permissions: Rechte eines Users, die zu diesem Dienst gehören (Schlüssel mit dem Präfix
 *   "&lt;dienst&gt;."). Ein Dienst sieht nie Rechte anderer Dienste.
 * - GET und PUT /api/service/self: der Dienst liest seine Daten und meldet Farbe und Status.
 */
@RestController
@RequestMapping("/api/service")
class ServiceApiController {

    /** Eingaben von PUT /self. Fehlende Felder bleiben unverändert, accentColor "" löscht die gemeldete Farbe. */
    record SelfUpdate(String accentColor, StatusBody status) {
    }

    record StatusBody(String state, Integer ttlSeconds) {
    }

    private static final int DEFAULT_TTL = 180;
    private static final int MIN_TTL = 30;
    private static final int MAX_TTL = 900;
    private static final int MAX_LOOKUP = 100;

    private final ServiceCaller caller;
    private final ServiceRepository services;
    private final ServiceHealthService health;
    private final AccountRepository accounts;
    private final PermissionService permissions;
    private final PermissionRepository permissionRepository;
    private final AuditLog audit;
    private final ClientIp clientIp;

    ServiceApiController(ServiceCaller caller, ServiceRepository services, ServiceHealthService health,
                         AccountRepository accounts, PermissionService permissions, PermissionRepository permissionRepository,
                         AuditLog audit, ClientIp clientIp) {
        this.caller = caller;
        this.services = services;
        this.health = health;
        this.accounts = accounts;
        this.permissions = permissions;
        this.permissionRepository = permissionRepository;
        this.audit = audit;
        this.clientIp = clientIp;
    }

    @GetMapping("/users/{sub}/permissions")
    ResponseEntity<Object> userPermissions(@AuthenticationPrincipal Jwt jwt, @PathVariable String sub,
                                           @RequestParam(required = false) String prefix) {
        ServiceInfo service = caller.require(jwt);
        UUID userId = parse(sub);
        AccountContact user = accounts.findContactById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_unknown"));

        String namespace = service.key() + ".";
        String filter = prefix == null || prefix.isEmpty() ? namespace : prefix;
        if (!filter.startsWith(namespace)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "prefix_not_allowed");
        }

        var effective = permissions.effective(userId);
        boolean access = !user.banned() && effective.getOrDefault(service.requiredPermission(), PermissionValue.NOT_SET).set();
        List<Map<String, Object>> list = new ArrayList<>();
        if (access) {
            for (PermissionDefinition d : permissionRepository.findAll()) {
                PermissionValue v = effective.getOrDefault(d.key(), PermissionValue.NOT_SET);
                if (d.key().startsWith(filter) && v.set()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", d.key());
                    entry.put("type", d.type());
                    if (d.isNumber()) {
                        if (v.unlimited()) {
                            entry.put("unlimited", true);
                        } else {
                            entry.put("value", v.number());
                        }
                    }
                    list.add(entry);
                }
            }
        }
        return ApiResponses.json(Map.of("sub", userId.toString(), "access", access, "permissions", list));
    }

    /**
     * Profile von Nutzern. Mit wiederholtem Parameter sub (höchstens 100) die genannten Nutzer, ohne sub alle Nutzer mit
     * Zugang zu diesem Dienst. Der Dienst speichert nur die UUID und holt Namen und Adresse bei Bedarf hier ab.
     */
    @GetMapping("/users")
    ResponseEntity<Object> users(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "sub", required = false) List<String> subs) {
        ServiceInfo service = caller.require(jwt);
        List<Map<String, Object>> list = new ArrayList<>();
        if (subs == null || subs.isEmpty()) {
            for (AccountContact c : accounts.findAllContacts()) {
                if (hasAccess(service, c)) {
                    list.add(profile(c, true));
                }
            }
        } else {
            if (subs.size() > MAX_LOOKUP) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "too_many_subs");
            }
            for (String sub : subs.stream().distinct().toList()) {
                accounts.findContactById(parse(sub)).ifPresent(c -> list.add(profile(c, hasAccess(service, c))));
            }
        }
        return ApiResponses.json(Map.of("users", list));
    }

    private boolean hasAccess(ServiceInfo service, AccountContact c) {
        return !c.banned() && permissions.has(c.id(), service.requiredPermission());
    }

    private static Map<String, Object> profile(AccountContact c, boolean access) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sub", c.id().toString());
        p.put("username", c.username());
        p.put("email", c.email());
        p.put("emailVerified", c.emailVerified());
        p.put("locale", c.language());
        p.put("banned", c.banned());
        p.put("access", access);
        return p;
    }

    @GetMapping("/self")
    ResponseEntity<Object> self(@AuthenticationPrincipal Jwt jwt) {
        ServiceInfo s = caller.require(jwt);
        var status = health.status(s.key());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", s.key());
        body.put("displayName", s.displayName());
        body.put("description", s.description());
        body.put("accentColor", s.accentColor());
        body.put("reportedAccentColor", s.reportedAccentColor());
        body.put("accentLocked", s.accentLocked());
        body.put("effectiveAccentColor", s.effectiveAccentColor());
        body.put("status", status.state());
        return ApiResponses.json(body);
    }

    @PutMapping("/self")
    ResponseEntity<Map<String, Object>> report(@AuthenticationPrincipal Jwt jwt, @RequestBody SelfUpdate body,
                                               HttpServletRequest request) {
        ServiceInfo service = caller.require(jwt);
        // Alles zuerst prüfen, dann übernehmen: Ein Fehler soll nichts halb ändern
        String color = null;
        boolean colorGiven = body.accentColor() != null;
        if (colorGiven) {
            color = body.accentColor().isBlank() ? null : validColor(body.accentColor());
        }
        Duration ttl = null;
        if (body.status() != null) {
            if (!"up".equals(body.status().state()) && !"down".equals(body.status().state())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "status_state_invalid");
            }
            int seconds = body.status().ttlSeconds() == null ? DEFAULT_TTL : body.status().ttlSeconds();
            if (seconds < MIN_TTL || seconds > MAX_TTL) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "status_ttl_invalid");
            }
            ttl = Duration.ofSeconds(seconds);
        }
        if (colorGiven) {
            services.setReportedColor(service.key(), color);
        }
        if (ttl != null) {
            health.report(service.key(), body.status().state(), ttl);
        }
        audit.record(null, null, "service.reported", Map.of("service", service.key(), "color_given", colorGiven,
                "status_given", ttl != null), clientIp.of(request));
        return ApiResponses.ok();
    }

    private static UUID parse(String sub) {
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sub_invalid");
        }
    }

    private static String validColor(String value) {
        if (!value.matches("#[0-9a-fA-F]{6}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_color_invalid");
        }
        return value.toLowerCase();
    }
}
