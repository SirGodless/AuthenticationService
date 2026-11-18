package net.anfall.auth.useradmin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Was der angemeldete User im Dashboard sehen darf. Nur für die Menüs der UI. Es ist keine Zugriffsentscheidung:
 * Jede Admin-Schnittstelle prüft Recht und 2FA selbst. Dieser Aufruf verlangt keine 2FA, damit die UI bei fehlender
 * 2FA den Weg zur Einrichtung zeigen kann.
 */
@RestController
class AdminOverviewController {

    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("users", "admin.users.view");
        SECTIONS.put("roles", "admin.roles.manage");
        SECTIONS.put("permissions", "admin.permissions.manage");
        SECTIONS.put("services", "admin.services.manage");
        SECTIONS.put("links", "admin.links.view_all");
        SECTIONS.put("audit", "admin.audit.view");
        SECTIONS.put("settings", "admin.settings.manage");
    }

    private final PermissionService permissions;
    private final AdminAccessGuard guard;

    AdminOverviewController(PermissionService permissions, AdminAccessGuard guard) {
        this.permissions = permissions;
        this.guard = guard;
    }

    @GetMapping("/api/admin/overview")
    ResponseEntity<Object> overview(@AuthenticationPrincipal AccountPrincipal me) {
        List<String> sections = SECTIONS.entrySet().stream().filter(e -> permissions.has(me.id(), e.getValue()))
                .map(Map.Entry::getKey).toList();
        return ApiResponses.json(Map.of("sections", sections, "isAdmin", permissions.hasAnyAdminPermission(me.id()),
                "twoFactorEnabled", guard.hasTwoFactor(me.id())));
    }
}
