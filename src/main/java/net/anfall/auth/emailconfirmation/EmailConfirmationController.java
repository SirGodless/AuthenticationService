package net.anfall.auth.emailconfirmation;

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

/** Schnittstelle der Seite "E-Mail-Adresse bestätigen" und von "Bestätigungsmail erneut senden". */
@RestController
@RequestMapping("/api/auth")
class EmailConfirmationController {

    record TokenBody(String token) {
    }

    record EmailBody(String email) {
    }

    private final EmailConfirmationService confirmation;
    private final ClientIp clientIp;

    EmailConfirmationController(EmailConfirmationService confirmation, ClientIp clientIp) {
        this.confirmation = confirmation;
        this.clientIp = clientIp;
    }

    /** Die Seite fragt, ob der Link noch gilt. Verbraucht ihn nicht (Mail-Scanner öffnen Links). */
    @GetMapping("/confirm-email/{token}")
    ResponseEntity<Map<String, Object>> check(@PathVariable String token) {
        if (!confirmation.isValid(token)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
        }
        return ApiResponses.valid();
    }

    @PostMapping("/confirm-email")
    ResponseEntity<Map<String, Object>> confirm(@RequestBody TokenBody body, HttpServletRequest request) {
        confirmation.confirm(body.token(), clientIp.of(request));
        return ApiResponses.ok();
    }

    @PostMapping("/resend-confirmation")
    ResponseEntity<Map<String, Object>> resend(@RequestBody EmailBody body, HttpServletRequest request) {
        confirmation.resend(body.email(), clientIp.of(request));
        return ApiResponses.ok();
    }
}
