package net.anfall.auth.invite;

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

/** Öffentliche Schnittstelle der Registrierungsseite (/invite/<token>). */
@RestController
@RequestMapping("/api/invite/{token}")
class InviteRegistrationController {

    record RegisterBody(String username, String email, String password, String language) {
    }

    private final InviteRegistrationService registration;
    private final ClientIp clientIp;

    InviteRegistrationController(InviteRegistrationService registration, ClientIp clientIp) {
        this.registration = registration;
        this.clientIp = clientIp;
    }

    @GetMapping
    ResponseEntity<Map<String, Object>> check(@PathVariable String token, HttpServletRequest request) {
        if (!registration.isValid(token, clientIp.of(request))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
        }
        return ApiResponses.valid();
    }

    @PostMapping("/register")
    ResponseEntity<Map<String, Object>> register(@PathVariable String token, @RequestBody RegisterBody b,
                                                 HttpServletRequest request) {
        registration.register(token, b.username(), b.email(), b.password(), b.language(), clientIp.of(request));
        return ApiResponses.ok();
    }
}
