package net.anfall.auth.role;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.permission.PermissionValueBody;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Dashboard-Seite "Rollen". Alles braucht admin.roles.manage. */
@RestController
@RequestMapping("/api/admin/roles")
class RoleAdminController {

    record NameBody(String name) {
    }

    private final RoleService roles;
    private final AdminAccessGuard guard;
    private final ClientIp clientIp;

    RoleAdminController(RoleService roles, AdminAccessGuard guard, ClientIp clientIp) {
        this.roles = roles;
        this.guard = guard;
        this.clientIp = clientIp;
    }

    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me) {
        guard.requireAny(me.id(), "admin.roles.manage", "admin.users.view");
        return ApiResponses.json(roles.list());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal AccountPrincipal me, @RequestBody NameBody b,
                                               HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        UUID id = roles.create(me.id(), b.name(), clientIp.of(request));
        return ApiResponses.ok(Map.of("status", "ok", "id", id.toString()));
    }

    @PatchMapping("/{id}")
    ResponseEntity<Map<String, Object>> rename(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                               @RequestBody NameBody b, HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        roles.rename(me.id(), id, b.name(), clientIp.of(request));
        return ApiResponses.ok();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                               HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        roles.delete(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PutMapping("/{id}/permissions/{key}")
    ResponseEntity<Map<String, Object>> setPermission(@AuthenticationPrincipal AccountPrincipal me,
                                                      @PathVariable UUID id, @PathVariable String key,
                                                      @RequestBody(required = false) PermissionValueBody body,
                                                      HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        var value = (body == null ? new PermissionValueBody(null, null) : body).toValue();
        roles.setPermission(me.id(), id, key, value, clientIp.of(request));
        return ApiResponses.ok();
    }

    @DeleteMapping("/{id}/permissions/{key}")
    ResponseEntity<Map<String, Object>> removePermission(@AuthenticationPrincipal AccountPrincipal me,
                                                         @PathVariable UUID id, @PathVariable String key,
                                                         HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        roles.removePermission(me.id(), id, key, clientIp.of(request));
        return ApiResponses.ok();
    }
}
