package net.anfall.auth.login;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.login.session.SessionService;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle der Login-Seite: Passwort, zweiter Faktor, Abmelden. */
@RestController
@RequestMapping("/api/auth")
class LoginController {

    record LoginBody(String identifier, String password, Boolean rememberMe) {
    }

    record CodeBody(String code) {
    }

    private final LoginService login;
    private final SecondFactorLoginService secondFactor;
    private final SessionService sessions;
    private final ClientIp clientIp;

    LoginController(LoginService login, SecondFactorLoginService secondFactor, SessionService sessions,
                    ClientIp clientIp) {
        this.login = login;
        this.secondFactor = secondFactor;
        this.sessions = sessions;
        this.clientIp = clientIp;
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginBody body, HttpServletRequest request,
                                              HttpServletResponse response) {
        LoginResult result = login.login(body.identifier(), body.password(), Boolean.TRUE.equals(body.rememberMe()),
                clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
        return ApiResponses.ok(Map.of("status", result.totpRequired() ? "totp_required" : "ok"));
    }

    @PostMapping("/login/second-factor")
    ResponseEntity<Map<String, Object>> secondFactor(@RequestBody CodeBody body, HttpServletRequest request,
                                                     HttpServletResponse response) {
        LoginResult result = secondFactor.verify(SessionService.tokenFrom(request), body.code(),
                clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
        return ApiResponses.ok();
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal AccountPrincipal me,
                                               HttpServletRequest request, HttpServletResponse response) {
        login.logout(SessionService.tokenFrom(request), me.id(), clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString());
        return ApiResponses.ok();
    }
}
