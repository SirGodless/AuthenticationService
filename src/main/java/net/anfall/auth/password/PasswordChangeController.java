package net.anfall.auth.password;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle für "Passwort ändern" (angemeldet). */
@RestController
class PasswordChangeController {

    record ChangeBody(String currentPassword, String newPassword) {
    }

    private final PasswordChangeService change;
    private final ClientIp clientIp;

    PasswordChangeController(PasswordChangeService change, ClientIp clientIp) {
        this.change = change;
        this.clientIp = clientIp;
    }

    @PostMapping("/api/auth/password")
    ResponseEntity<Map<String, Object>> change(@AuthenticationPrincipal AccountPrincipal me,
                                               @RequestBody ChangeBody body, HttpServletRequest request) {
        change.change(me.id(), SessionService.tokenFrom(request), body.currentPassword(), body.newPassword(),
                clientIp.of(request));
        return ApiResponses.ok();
    }
}
