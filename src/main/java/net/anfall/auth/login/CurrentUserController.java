package net.anfall.auth.login;

import java.util.Map;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Wer bin ich?" Die UI ruft das beim Laden auf und bekommt dabei auch das CSRF-Cookie. */
@RestController
class CurrentUserController {

    private final AccountRepository accounts;

    CurrentUserController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        return ApiResponses.ok(Map.of("id", me.id().toString(), "username", me.username(),
                "language", accounts.language(me.id())));
    }
}
