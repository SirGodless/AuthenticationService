package net.anfall.auth.account;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ApiResponses;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Sprache und Darstellung (system, hell, dunkel) des eigenen Kontos ändern (Profilseite). Mails kommen in der Sprache des Kontos. */
@RestController
class AccountSettingsController {

    record Body(String language) {
    }

    record ThemeBody(String theme) {
    }

    private final AccountRepository accounts;
    private final AuditLog audit;
    private final ClientIp clientIp;

    AccountSettingsController(AccountRepository accounts, AuditLog audit, ClientIp clientIp) {
        this.accounts = accounts;
        this.audit = audit;
        this.clientIp = clientIp;
    }

    @PutMapping("/api/auth/theme")
    ResponseEntity<Map<String, Object>> setTheme(@AuthenticationPrincipal AccountPrincipal me, @RequestBody ThemeBody body,
                                                 HttpServletRequest request) {
        if (body.theme() == null || !java.util.List.of("system", "light", "dark").contains(body.theme())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "theme_invalid");
        }
        accounts.setTheme(me.id(), body.theme());
        audit.record(me.id(), me.id(), "account.theme_changed", Map.of("theme", body.theme()), clientIp.of(request));
        return ApiResponses.ok();
    }

    @PutMapping("/api/auth/language")
    ResponseEntity<Map<String, Object>> set(@AuthenticationPrincipal AccountPrincipal me, @RequestBody Body body,
                                            HttpServletRequest request) {
        if (!AccountInputRules.isValidLanguage(body.language())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "language_invalid");
        }
        accounts.setLanguage(me.id(), body.language());
        audit.record(me.id(), me.id(), "account.language_changed", Map.of("language", body.language()), clientIp.of(request));
        return ApiResponses.ok();
    }
}
