package net.anfall.auth.audit;

import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Dashboard-Seite "Protokoll". Braucht admin.audit.view. */
@RestController
@RequestMapping("/api/admin/audit")
class AuditAdminController {

    private final AuditQueryService audit;
    private final AdminAccessGuard guard;

    AuditAdminController(AuditQueryService audit, AdminAccessGuard guard) {
        this.audit = audit;
        this.guard = guard;
    }

    @GetMapping
    ResponseEntity<Object> list(@AuthenticationPrincipal AccountPrincipal me,
                                @RequestParam(defaultValue = "") String action,
                                @RequestParam(defaultValue = "") String user,
                                @RequestParam(required = false) Long before,
                                @RequestParam(defaultValue = "50") int limit) {
        guard.require(me.id(), "admin.audit.view");
        return ApiResponses.json(audit.query(action, user, before, limit));
    }
}
