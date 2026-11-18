package net.anfall.auth.password;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Seiten "Passwort vergessen" und "Neues Passwort wählen". */
@RestController
@RequestMapping("/api/auth/password-reset")
class PasswordResetController {

    record EmailBody(String email) {
    }

    record ConfirmBody(String token, String password) {
    }

    private final PasswordResetService reset;
    private final ClientIp clientIp;

    PasswordResetController(PasswordResetService reset, ClientIp clientIp) {
        this.reset = reset;
        this.clientIp = clientIp;
    }

    @PostMapping("/request")
    ResponseEntity<Map<String, Object>> request(@RequestBody EmailBody body, HttpServletRequest request) {
        reset.request(body.email(), clientIp.of(request));
        return ApiResponses.ok();
    }

    /** Die Seite fragt, ob der Link noch gilt. */
    @GetMapping("/{token}")
    ResponseEntity<Map<String, Object>> check(@PathVariable String token) {
        if (!reset.isValid(token)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
        }
        return ApiResponses.valid();
    }

    @PostMapping("/confirm")
    ResponseEntity<Map<String, Object>> confirm(@RequestBody ConfirmBody body, HttpServletRequest request) {
        reset.confirm(body.token(), body.password(), clientIp.of(request));
        return ApiResponses.ok();
    }
}
