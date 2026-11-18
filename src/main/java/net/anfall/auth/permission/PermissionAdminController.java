package net.anfall.auth.permission;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Dashboard-Seite "Rechte". Anlegen, ändern, löschen braucht admin.permissions.manage. */
@RestController
@RequestMapping("/api/admin/permissions")
class PermissionAdminController {

    record CreateBody(String key, String type, String direction, Boolean allowUnlimited, Boolean linkGrantable,
                      String description) {
    }

    record UpdateBody(String description, Boolean linkGrantable) {
    }

    private final PermissionDefinitionService definitions;
    private final AdminAccessGuard guard;
    private final ClientIp clientIp;

    PermissionAdminController(PermissionDefinitionService definitions, AdminAccessGuard guard, ClientIp clientIp) {
        this.definitions = definitions;
        this.guard = guard;
        this.clientIp = clientIp;
    }

    /** Die Liste brauchen auch die Dialoge für Rollen und User, deshalb genügt eines dieser Rechte. */
    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me) {
        guard.requireAny(me.id(), "admin.permissions.manage", "admin.roles.manage", "admin.users.view");
        return ApiResponses.json(definitions.list());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal AccountPrincipal me, @RequestBody CreateBody b,
                                               HttpServletRequest request) {
        guard.require(me.id(), "admin.permissions.manage");
        definitions.create(me.id(), new PermissionDefinition(b.key(), b.type(), blankToNull(b.direction()),
                Boolean.TRUE.equals(b.allowUnlimited()), Boolean.TRUE.equals(b.linkGrantable()), false,
                b.description() == null ? "" : b.description()), clientIp.of(request));
        return ApiResponses.ok();
    }

    @PatchMapping("/{key}")
    ResponseEntity<Map<String, Object>> update(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String key,
                                               @RequestBody UpdateBody b, HttpServletRequest request) {
        guard.require(me.id(), "admin.permissions.manage");
        definitions.update(me.id(), key, b.description(), Boolean.TRUE.equals(b.linkGrantable()), clientIp.of(request));
        return ApiResponses.ok();
    }

    @DeleteMapping("/{key}")
    ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String key,
                                               HttpServletRequest request) {
        guard.require(me.id(), "admin.permissions.manage");
        definitions.delete(me.id(), key, clientIp.of(request));
        return ApiResponses.ok();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
