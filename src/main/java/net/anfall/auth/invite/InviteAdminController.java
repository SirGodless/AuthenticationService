package net.anfall.auth.invite;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.login.session.AccountPrincipal;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Seiten zu Einladungen: alle Links (admin.links.view_all, auch zum Deaktivieren), IP-Sperren sehen und aufheben und
 * die Grenzen der Gültigkeit ändern (admin.settings.manage).
 */
@RestController
class InviteAdminController {

    record LimitsBody(int minHours, int defaultDays, int maxDays, int privilegedMaxDays) {
    }

    private final InviteLinkService links;
    private final InviteIpBlockService blocks;
    private final InviteLimitsService limits;
    private final AdminAccessGuard guard;
    private final ClientIp clientIp;

    InviteAdminController(InviteLinkService links, InviteIpBlockService blocks, InviteLimitsService limits,
                          AdminAccessGuard guard, ClientIp clientIp) {
        this.links = links;
        this.blocks = blocks;
        this.limits = limits;
        this.guard = guard;
        this.clientIp = clientIp;
    }

    @GetMapping("/api/admin/invites")
    ResponseEntity<Object> all(@AuthenticationPrincipal AccountPrincipal me) {
        guard.require(me.id(), "admin.links.view_all");
        return ApiResponses.json(links.listAll().stream().map(InviteLinkView::of).toList());
    }

    @PostMapping("/api/admin/invites/{id}/deactivate")
    ResponseEntity<Map<String, Object>> deactivate(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                   HttpServletRequest request) {
        guard.require(me.id(), "admin.links.view_all");
        links.deactivateAny(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }

    @GetMapping("/api/admin/settings/invites")
    ResponseEntity<Object> settings(@AuthenticationPrincipal AccountPrincipal me) {
        guard.require(me.id(), "admin.settings.manage");
        return ApiResponses.json(Map.of("limits", limits.current(), "hardMaxDays", InviteLimits.HARD_MAX_DAYS,
                "blocks", blocks.activeBlocks()));
    }

    @PutMapping("/api/admin/settings/invites")
    ResponseEntity<Map<String, Object>> updateLimits(@AuthenticationPrincipal AccountPrincipal me, @RequestBody LimitsBody b,
                                                     HttpServletRequest request) {
        guard.require(me.id(), "admin.settings.manage");
        limits.update(me.id(), new InviteLimits(b.minHours(), b.defaultDays(), b.maxDays(), b.privilegedMaxDays()), clientIp.of(request));
        return ApiResponses.ok();
    }

    /** ip_key enthält bei IPv6 einen Schrägstrich (/64), deshalb als Parameter statt im Pfad. */
    @DeleteMapping("/api/admin/settings/invites/blocks")
    ResponseEntity<Map<String, Object>> liftBlock(@AuthenticationPrincipal AccountPrincipal me,
                                                  @org.springframework.web.bind.annotation.RequestParam String ipKey,
                                                  HttpServletRequest request) {
        guard.require(me.id(), "admin.settings.manage");
        blocks.lift(me.id(), ipKey, clientIp.of(request));
        return ApiResponses.ok();
    }
}
