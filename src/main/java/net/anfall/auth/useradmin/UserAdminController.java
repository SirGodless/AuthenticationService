package net.anfall.auth.useradmin;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountBanService;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.permission.PermissionValueBody;
import net.anfall.auth.role.RoleService;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schnittstelle der Dashboard-Seite "Benutzer". Jede Aktion hat ihr eigenes Recht:
 * Ansehen admin.users.view, Bann admin.users.ban, 2FA zurücksetzen admin.users.reset_2fa,
 * Passwort-Reset erzwingen admin.users.force_password_reset, Rollen admin.roles.manage, direkte Rechte admin.permissions.manage.
 */
@RestController
@RequestMapping("/api/admin/users")
class UserAdminController {

    private final UserAdminService users;
    private final AccountBanService bans;
    private final ForcedPasswordResetService forcedReset;
    private final RoleService roles;
    private final AdminAccessGuard guard;
    private final ClientIp clientIp;

    UserAdminController(UserAdminService users, AccountBanService bans, ForcedPasswordResetService forcedReset,
                        RoleService roles, AdminAccessGuard guard, ClientIp clientIp) {
        this.users = users;
        this.bans = bans;
        this.forcedReset = forcedReset;
        this.roles = roles;
        this.guard = guard;
        this.clientIp = clientIp;
    }

    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me,
                                @RequestParam(defaultValue = "") String q) {
        guard.require(me.id(), "admin.users.view");
        return ApiResponses.json(users.list(q));
    }

    @GetMapping("/{id}")
    ResponseEntity<Object> detail(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id) {
        guard.require(me.id(), "admin.users.view");
        return ApiResponses.json(users.detail(id));
    }

    @PostMapping("/{id}/ban")
    ResponseEntity<Map<String, Object>> ban(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                            HttpServletRequest request) {
        guard.require(me.id(), "admin.users.ban");
        bans.ban(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PostMapping("/{id}/unban")
    ResponseEntity<Map<String, Object>> unban(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                              HttpServletRequest request) {
        guard.require(me.id(), "admin.users.ban");
        bans.unban(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PostMapping("/{id}/reset-2fa")
    ResponseEntity<Map<String, Object>> reset2fa(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                 HttpServletRequest request) {
        guard.require(me.id(), "admin.users.reset_2fa");
        users.reset2fa(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PostMapping("/{id}/force-password-reset")
    ResponseEntity<Map<String, Object>> forcePasswordReset(@AuthenticationPrincipal AccountPrincipal me,
                                                           @PathVariable UUID id, HttpServletRequest request) {
        guard.require(me.id(), "admin.users.force_password_reset");
        forcedReset.force(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PutMapping("/{id}/roles/{roleId}")
    ResponseEntity<Map<String, Object>> assignRole(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                   @PathVariable UUID roleId, HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        roles.assign(me.id(), id, roleId, clientIp.of(request));
        return ApiResponses.ok();
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    ResponseEntity<Map<String, Object>> unassignRole(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                     @PathVariable UUID roleId, HttpServletRequest request) {
        guard.require(me.id(), "admin.roles.manage");
        roles.unassign(me.id(), id, roleId, clientIp.of(request));
        return ApiResponses.ok();
    }

    @PutMapping("/{id}/permissions/{key}")
    ResponseEntity<Map<String, Object>> setPermission(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                      @PathVariable String key,
                                                      @RequestBody(required = false) PermissionValueBody body,
                                                      HttpServletRequest request) {
        guard.require(me.id(), "admin.permissions.manage");
        var value = (body == null ? new PermissionValueBody(null, null) : body).toValue();
        users.setDirectPermission(me.id(), id, key, value, clientIp.of(request));
        return ApiResponses.ok();
    }

    @DeleteMapping("/{id}/permissions/{key}")
    ResponseEntity<Map<String, Object>> removePermission(@AuthenticationPrincipal AccountPrincipal me,
                                                         @PathVariable UUID id, @PathVariable String key,
                                                         HttpServletRequest request) {
        guard.require(me.id(), "admin.permissions.manage");
        users.removeDirectPermission(me.id(), id, key, clientIp.of(request));
        return ApiResponses.ok();
    }
}
