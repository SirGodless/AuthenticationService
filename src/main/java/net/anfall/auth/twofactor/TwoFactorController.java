package net.anfall.auth.twofactor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Seite "Zwei-Faktor-Authentifizierung" (für jeden angemeldeten User). */
@RestController
@RequestMapping("/api/auth/2fa")
class TwoFactorController {

    record PasswordBody(String password) {
    }

    record CodeBody(String code) {
    }

    record PasswordAndCodeBody(String password, String code) {
    }

    private final TwoFactorSettingsService settings;
    private final ClientIp clientIp;

    TwoFactorController(TwoFactorSettingsService settings, ClientIp clientIp) {
        this.settings = settings;
        this.clientIp = clientIp;
    }

    @GetMapping
    ResponseEntity<Object> status(@AuthenticationPrincipal AccountPrincipal me) {
        return ApiResponses.json(settings.status(me.id()));
    }

    @PostMapping("/enrollment")
    ResponseEntity<Object> start(@AuthenticationPrincipal AccountPrincipal me, @RequestBody PasswordBody b,
                                 HttpServletRequest request) {
        return ApiResponses.json(settings.startEnrollment(me.id(), b.password(), clientIp.of(request)));
    }

    @PostMapping("/enrollment/confirm")
    ResponseEntity<Map<String, Object>> confirm(@AuthenticationPrincipal AccountPrincipal me, @RequestBody CodeBody b,
                                                HttpServletRequest request) {
        return ApiResponses.ok(Map.of("recoveryCodes", settings.confirmEnrollment(me.id(), b.code(), clientIp.of(request))));
    }

    @PostMapping("/recovery-codes")
    ResponseEntity<Map<String, Object>> regenerate(@AuthenticationPrincipal AccountPrincipal me,
                                                   @RequestBody PasswordAndCodeBody b, HttpServletRequest request) {
        return ApiResponses.ok(Map.of("recoveryCodes",
                settings.regenerateRecoveryCodes(me.id(), b.password(), b.code(), clientIp.of(request))));
    }

    @PostMapping("/disable")
    ResponseEntity<Map<String, Object>> disable(@AuthenticationPrincipal AccountPrincipal me,
                                                @RequestBody PasswordAndCodeBody b, HttpServletRequest request) {
        settings.disable(me.id(), SessionService.tokenFrom(request), b.password(), b.code(), clientIp.of(request));
        return ApiResponses.ok();
    }
}
