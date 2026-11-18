package net.anfall.auth.setup;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schnittstelle für die Setup-Seite der UI. Der Token steht im Pfad. */
@RestController
@RequestMapping("/api/setup/{token}")
public class SetupController {

    private final SetupTokenService tokens;
    private final FirstAdminService firstAdmin;
    private final AppProperties props;
    private final ClientIp clientIp;

    public SetupController(SetupTokenService tokens, FirstAdminService firstAdmin, AppProperties props, ClientIp clientIp) {
        this.tokens = tokens;
        this.firstAdmin = firstAdmin;
        this.props = props;
        this.clientIp = clientIp;
    }

    /** Die UI fragt beim Öffnen der Seite, ob der Link gültig ist. */
    @GetMapping
    ResponseEntity<Map<String, Object>> check(@PathVariable String token) {
        tokens.check(token);
        return noStore(Map.of("valid", true));
    }

    /** Erzeugt (oder wiederholt) das TOTP-Geheimnis. Die UI baut daraus den QR-Code. */
    @PostMapping("/prepare")
    ResponseEntity<Map<String, Object>> prepare(@PathVariable String token) {
        String secret = firstAdmin.prepareTotp(token);
        return noStore(Map.of("secret", secret, "issuer", props.totpIssuer()));
    }

    @PostMapping("/complete")
    ResponseEntity<Map<String, Object>> complete(@PathVariable String token,
                                                 @RequestBody FirstAdminService.CompleteRequest body,
                                                 HttpServletRequest request) {
        List<String> codes = firstAdmin.complete(token, body, clientIp.of(request));
        return noStore(Map.of("recoveryCodes", codes));
    }

    // Antworten mit Geheimnissen dürfen nicht zwischengespeichert werden
    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
