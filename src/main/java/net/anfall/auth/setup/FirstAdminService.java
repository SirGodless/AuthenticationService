package net.anfall.auth.setup;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.anfall.auth.account.AccountService;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.twofactor.RecoveryCodes;
import net.anfall.auth.twofactor.Totp;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Einrichtung des ersten Admins über den Setup-Link: TOTP vorbereiten, Konto samt 2FA und Rolle anlegen. */
@Service
public class FirstAdminService {

    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Eingaben der Setup-Seite. */
    public record CompleteRequest(String username, String email, String password, String language, String totpCode) {
    }

    private final SetupTokenService tokens;
    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final AccountService accounts;
    private final AuditLog audit;

    public FirstAdminService(SetupTokenService tokens, JdbcClient jdbc, SecretCipher cipher, AccountService accounts,
                             AuditLog audit) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Liefert das TOTP-Geheimnis für die Anzeige. Beim zweiten Aufruf dasselbe, solange der Link gilt. */
    @Transactional
    public String prepareTotp(String token) {
        SetupTokenService.ValidToken valid = tokens.loadValid(token);
        byte[] secret;
        if (valid.pendingTotpEnc() == null) {
            secret = Totp.newSecret();
            tokens.storePendingTotp(valid.id(), cipher.encrypt(secret));
        } else {
            secret = cipher.decrypt(valid.pendingTotpEnc());
        }
        return Totp.toBase32(secret);
    }

    /** Legt den ersten Admin an. Gibt die Wiederherstellungscodes zurück (werden nur jetzt einmal angezeigt). */
    @Transactional
    public List<String> complete(String token, CompleteRequest req, String ip) {
        SetupTokenService.ValidToken valid = tokens.loadValid(token);

        // Gleiche Regeln wie überall: Username, E-Mail, Sprache, Passwort (inkl. Sperrliste), Eindeutigkeit
        AccountService.NewAccount account = accounts.validate(req.username(), req.email(), req.password(),
                req.language());

        // 2FA ist Pflicht: Ohne gültigen Code aus der App wird kein Admin angelegt.
        if (valid.pendingTotpEnc() == null) {
            throw bad("totp_not_prepared");
        }
        byte[] totpSecret = cipher.decrypt(valid.pendingTotpEnc());
        OptionalLong step = Totp.verify(totpSecret, req.totpCode(), System.currentTimeMillis(), null);
        if (step.isEmpty()) {
            throw bad("totp_code_invalid");
        }

        // Die E-Mail gilt NUR HIER als bestätigt: Wer den Setup-Link aus dem Server-Log hat, ist der Betreiber.
        // Alle anderen Konten müssen ihre Adresse per Mail bestätigen.
        UUID userId = accounts.create(account, true, null);
        jdbc.sql("INSERT INTO user_totp (user_id, secret_enc, last_used_step) VALUES (:id, :s, :step)")
                .param("id", userId).param("s", valid.pendingTotpEnc()).param("step", step.getAsLong()).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)")
                .param("u", userId).param("r", ADMIN_ROLE_ID).update();

        List<String> codes = RecoveryCodes.generate();
        for (String code : codes) {
            jdbc.sql("INSERT INTO recovery_codes (id, user_id, code_hash) VALUES (:id, :u, :h)")
                    .param("id", UUID.randomUUID()).param("u", userId).param("h", RecoveryCodes.hash(code)).update();
        }

        tokens.markUsed(valid.id());
        audit.record(userId, userId, "setup.admin_created", Map.of("role", "Admin"), ip);
        return codes;
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }
}
