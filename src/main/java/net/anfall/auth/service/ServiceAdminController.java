package net.anfall.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard-Reiter "Dienste". Braucht admin.services.manage. */
@RestController
class ServiceAdminController {

    private final ServiceAdminService service;
    private final AdminAccessGuard guard;
    private final ClientIp clientIp;

    ServiceAdminController(ServiceAdminService service, AdminAccessGuard guard, ClientIp clientIp) {
        this.service = service;
        this.guard = guard;
        this.clientIp = clientIp;
    }

    @GetMapping("/api/admin/services")
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me) {
        guard.require(me.id(), "admin.services.manage");
        return ApiResponses.json(service.list());
    }

    @PatchMapping("/api/admin/services/{key}")
    ResponseEntity<Map<String, Object>> update(@AuthenticationPrincipal AccountPrincipal me, @PathVariable String key,
                                               @RequestBody ServiceAdminService.Update body, HttpServletRequest request) {
        guard.require(me.id(), "admin.services.manage");
        service.update(me.id(), key, body, clientIp.of(request));
        return ApiResponses.ok();
    }
}
