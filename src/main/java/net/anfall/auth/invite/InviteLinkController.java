package net.anfall.auth.invite;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Eigene Links erzeugen, ansehen und deaktivieren (Profilseite). Ob jemand das darf, entscheidet das Backend ({@code links.create}). */
@RestController
@RequestMapping("/api/invites")
class InviteLinkController {

    record CreateBody(Integer validityHours, UUID roleId, List<Grant> permissions) {
    }

    private final InviteLinkService service;
    private final ClientIp clientIp;

    InviteLinkController(InviteLinkService service, ClientIp clientIp) {
        this.service = service;
        this.clientIp = clientIp;
    }

    @GetMapping("/options")
    ResponseEntity<Object> options(@AuthenticationPrincipal AccountPrincipal me) {
        return ApiResponses.json(service.options(me.id()));
    }

    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me) {
        return ApiResponses.json(service.listOwn(me.id()).stream().map(InviteLinkView::of).toList());
    }

    @PostMapping
    ResponseEntity<Object> create(@AuthenticationPrincipal AccountPrincipal me, @RequestBody CreateBody b,
                                  HttpServletRequest request) {
        return ApiResponses.json(service.create(me.id(), b.validityHours(), b.roleId(), b.permissions(), clientIp.of(request)));
    }

    @PostMapping("/{id}/deactivate")
    ResponseEntity<Map<String, Object>> deactivate(@AuthenticationPrincipal AccountPrincipal me, @PathVariable UUID id,
                                                   HttpServletRequest request) {
        service.deactivateOwn(me.id(), id, clientIp.of(request));
        return ApiResponses.ok();
    }
}
