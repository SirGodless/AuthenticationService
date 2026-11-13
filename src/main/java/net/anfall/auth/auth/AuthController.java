package net.anfall.auth.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import net.anfall.auth.security.AccountPrincipal;
import net.anfall.auth.security.SessionService;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle für Login, Mail-Links, Passwort. Dünn gehalten: Die Logik steckt in den Services. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginService login;
    private final EmailConfirmationService confirmation;
    private final PasswordResetService passwordReset;
    private final ClientIp clientIp;
    private final JdbcClient jdbc;
    private final SessionService sessions;

    public AuthController(LoginService login, EmailConfirmationService confirmation,
                          PasswordResetService passwordReset, ClientIp clientIp, JdbcClient jdbc,
                          SessionService sessions) {
        this.login = login;
        this.confirmation = confirmation;
        this.passwordReset = passwordReset;
        this.clientIp = clientIp;
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    record LoginRequest(String identifier, String password, Boolean rememberMe) {
    }

    record CodeRequest(String code) {
    }

    record TokenRequest(String token) {
    }

    record EmailRequest(String email) {
    }

    record ResetConfirmRequest(String token, String password) {
    }

    record ChangePasswordRequest(String currentPassword, String newPassword) {
    }

    /** Wer bin ich? Die UI ruft das beim Laden auf (und bekommt dabei auch das CSRF-Cookie). */
    @GetMapping("/me")
    ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal AccountPrincipal me) {
        if (me == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated");
        }
        String language = jdbc.sql("SELECT language FROM users WHERE id = :id").param("id", me.id())
                .query(String.class).single();
        return noStore(Map.of("id", me.id().toString(), "username", me.username(), "language", language));
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body, HttpServletRequest request,
                                              HttpServletResponse response) {
        var result = login.login(body.identifier(), body.password(), Boolean.TRUE.equals(body.rememberMe()),
                clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
        return noStore(Map.of("status", result.totpRequired() ? "totp_required" : "ok"));
    }

    @PostMapping("/login/second-factor")
    ResponseEntity<Map<String, Object>> secondFactor(@RequestBody CodeRequest body, HttpServletRequest request,
                                                     HttpServletResponse response) {
        var result = login.secondFactor(SessionService.tokenFrom(request), body.code(), clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, result.cookie().toString());
        return noStore(Map.of("status", "ok"));
    }

    @PostMapping("/logout")
    ResponseEntity<Map<String, Object>> logout(@AuthenticationPrincipal AccountPrincipal me,
                                               HttpServletRequest request, HttpServletResponse response) {
        login.logout(SessionService.tokenFrom(request), me.id(), clientIp.of(request));
        response.addHeader(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString());
        return noStore(Map.of("status", "ok"));
    }

    @PostMapping("/password")
    ResponseEntity<Map<String, Object>> changePassword(@AuthenticationPrincipal AccountPrincipal me,
                                                       @RequestBody ChangePasswordRequest body,
                                                       HttpServletRequest request) {
        passwordReset.change(me.id(), SessionService.tokenFrom(request), body.currentPassword(),
                body.newPassword(), clientIp.of(request));
        return noStore(Map.of("status", "ok"));
    }

    // ---- E-Mail bestätigen ----

    /** Die Seite fragt, ob der Link noch gilt. Verbraucht ihn nicht (Mail-Scanner öffnen Links). */
    @GetMapping("/confirm-email/{token}")
    ResponseEntity<Map<String, Object>> checkConfirm(@PathVariable String token) {
        if (!confirmation.isValid(token)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
        }
        return noStore(Map.of("valid", true));
    }

    @PostMapping("/confirm-email")
    ResponseEntity<Map<String, Object>> confirm(@RequestBody TokenRequest body, HttpServletRequest request) {
        confirmation.confirm(body.token(), clientIp.of(request));
        return noStore(Map.of("status", "ok"));
    }

    @PostMapping("/resend-confirmation")
    ResponseEntity<Map<String, Object>> resend(@RequestBody EmailRequest body, HttpServletRequest request) {
        confirmation.resend(body.email(), clientIp.of(request));
        return noStore(Map.of("status", "ok"));
    }

    // ---- Passwort vergessen ----

    @PostMapping("/password-reset/request")
    ResponseEntity<Map<String, Object>> resetRequest(@RequestBody EmailRequest body, HttpServletRequest request) {
        passwordReset.request(body.email(), clientIp.of(request));
        return noStore(Map.of("status", "ok"));
    }

    @GetMapping("/password-reset/{token}")
    ResponseEntity<Map<String, Object>> resetCheck(@PathVariable String token) {
        if (!passwordReset.isValid(token)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
        }
        return noStore(Map.of("valid", true));
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Map<String, Object>> resetConfirm(@RequestBody ResetConfirmRequest body,
                                                     HttpServletRequest request) {
        passwordReset.confirm(body.token(), body.password(), clientIp.of(request));
        return noStore(Map.of("status", "ok"));
    }

    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
