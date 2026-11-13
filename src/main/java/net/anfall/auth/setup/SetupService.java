package net.anfall.auth.setup;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.RecoveryCodes;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.crypto.Totp;
import net.anfall.auth.user.AccountService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Einrichtung des ersten Admins über den Setup-Link.
 *
 * Garantien:
 * - Der Link gilt einmal, läuft ab und wird erst beim erfolgreichen Absenden verbraucht.
 * - Alle Schritte sitzen hinter einer Datenbank-Sperre. Zwei gleichzeitige Anfragen können
 *   deshalb nie zwei Admins erzeugen, auch nicht mit zwei verschiedenen gültigen Links.
 * - Sobald ein Admin existiert, antwortet jeder Setup-Aufruf wie ein ungültiger Link.
 */
@Service
public class SetupService {

    /** Beliebige feste Zahl, dient nur als Name der Sperre. */
    private static final long LOCK_ID = 4_711_001L;
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcClient jdbc;
    private final AppProperties props;
    private final SecretCipher cipher;
    private final AccountService accounts;
    private final AuditLog audit;

    public SetupService(JdbcClient jdbc, AppProperties props, SecretCipher cipher,
                        AccountService accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.cipher = cipher;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Es gibt keinen Sonderstatus Admin: Admin ist, wer die eingebaute Rolle mit allen admin.* Rechten hat. */
    public boolean adminExists() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                                       WHERE r.all_admin_permissions)
                        """)
                .query(Boolean.class).single();
    }

    /** Erzeugt einen neuen Setup-Token und macht alle noch offenen alten Tokens ungültig. */
    @Transactional
    public String issueToken() {
        lock();
        jdbc.sql("DELETE FROM setup_tokens WHERE used_at IS NULL").update();
        String token = Tokens.newToken();
        Duration ttl = props.setupTokenTtl();
        jdbc.sql("""
                        INSERT INTO setup_tokens (id, token_hash, expires_at)
                        VALUES (:id, :hash, now() + make_interval(secs => :secs))
                        """)
                .param("id", UUID.randomUUID())
                .param("hash", Tokens.sha256Hex(token))
                .param("secs", (double) ttl.toSeconds())
                .update();
        return token;
    }

    /** Prüft nur, ob der Link gültig ist. Wirft sonst die neutrale Fehlermeldung. */
    @Transactional
    public void check(String token) {
        loadValidToken(token);
    }

    /** Liefert das TOTP-Geheimnis für die Anzeige. Beim zweiten Aufruf dasselbe, solange der Link gilt. */
    @Transactional
    public String prepareTotp(String token) {
        TokenRow row = loadValidToken(token);
        byte[] secret;
        if (row.pendingTotpEnc() == null) {
            secret = Totp.newSecret();
            jdbc.sql("UPDATE setup_tokens SET pending_totp_enc = :enc WHERE id = :id")
                    .param("enc", cipher.encrypt(secret))
                    .param("id", row.id())
                    .update();
        } else {
            secret = cipher.decrypt(row.pendingTotpEnc());
        }
        return Totp.toBase32(secret);
    }

    /** Legt den ersten Admin an. Gibt die Wiederherstellungscodes zurück (werden nur jetzt einmal angezeigt). */
    @Transactional
    public List<String> complete(String token, CompleteRequest req, String ip) {
        TokenRow row = loadValidToken(token);

        // Gleiche Regeln wie überall: Username, E-Mail, Sprache, Passwort (inkl. Sperrliste), Eindeutigkeit
        AccountService.NewAccount account = accounts.validate(req.username(), req.email(), req.password(),
                req.language());

        // 2FA ist Pflicht: Ohne gültigen Code aus der App wird kein Admin angelegt.
        if (row.pendingTotpEnc() == null) {
            throw bad("totp_not_prepared");
        }
        byte[] totpSecret = cipher.decrypt(row.pendingTotpEnc());
        OptionalLong step = Totp.verify(totpSecret, req.totpCode(), System.currentTimeMillis(), null);
        if (step.isEmpty()) {
            throw bad("totp_code_invalid");
        }

        // Die E-Mail gilt NUR HIER als bestätigt: Wer den Setup-Link aus dem Server-Log hat, ist der Betreiber.
        // Alle anderen Konten müssen ihre Adresse per Mail bestätigen.
        UUID userId = accounts.insert(account, true, null);
        jdbc.sql("INSERT INTO user_totp (user_id, secret_enc, last_used_step) VALUES (:id, :s, :step)")
                .param("id", userId).param("s", row.pendingTotpEnc()).param("step", step.getAsLong()).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)")
                .param("u", userId).param("r", ADMIN_ROLE_ID).update();

        List<String> codes = RecoveryCodes.generate();
        for (String code : codes) {
            jdbc.sql("INSERT INTO recovery_codes (id, user_id, code_hash) VALUES (:id, :u, :h)")
                    .param("id", UUID.randomUUID()).param("u", userId).param("h", RecoveryCodes.hash(code)).update();
        }

        // Link verbrauchen und das Geheimnis aus der Zwischenablage der Tabelle entfernen
        jdbc.sql("UPDATE setup_tokens SET used_at = now(), pending_totp_enc = NULL WHERE id = :id")
                .param("id", row.id()).update();

        audit.record(userId, userId, "setup.admin_created", Map.of("role", "Admin"), ip);
        return codes;
    }

    // ---- intern ----

    private record TokenRow(UUID id, byte[] pendingTotpEnc) {
    }

    /**
     * Sperrt die Einrichtung für andere Transaktionen, prüft den Link und sperrt seine Zeile.
     * Ungültig, abgelaufen, benutzt oder Admin schon vorhanden: immer dieselbe Antwort.
     */
    private TokenRow loadValidToken(String token) {
        lock();
        if (adminExists()) {
            throw invalidLink();
        }
        return jdbc.sql("""
                        SELECT id, pending_totp_enc FROM setup_tokens
                        WHERE token_hash = :hash AND used_at IS NULL AND expires_at > now()
                        FOR UPDATE
                        """)
                .param("hash", Tokens.sha256Hex(token == null ? "" : token))
                .query((rs, n) -> new TokenRow(rs.getObject("id", UUID.class), rs.getBytes("pending_totp_enc")))
                .optional()
                .orElseThrow(SetupService::invalidLink);
    }

    /** Transaktionsweite Sperre, wird beim Ende der Transaktion automatisch freigegeben. */
    private void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:id)").param("id", LOCK_ID).query().singleRow();
    }

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public record CompleteRequest(String username, String email, String password, String language, String totpCode) {
    }
}
