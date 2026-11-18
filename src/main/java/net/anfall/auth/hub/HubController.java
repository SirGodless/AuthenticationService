package net.anfall.auth.hub;

import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Die Daten der Hub-Startseite und der Sidebar. */
@RestController
class HubController {

    private final HubService hub;

    HubController(HubService hub) {
        this.hub = hub;
    }

    @GetMapping("/api/hub")
    ResponseEntity<Object> hub(@AuthenticationPrincipal AccountPrincipal me) {
        return ApiResponses.json(hub.forUser(me));
    }
}
