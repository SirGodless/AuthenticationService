package net.anfall.auth.setup;

import java.time.Duration;
import java.util.UUID;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Der Setup-Link für den ersten Admin: erzeugen, prüfen, sperren.
 *
 * Garantien (zusammen mit {@link FirstAdminService}):
 * - Der Link gilt einmal, läuft ab und wird erst beim erfolgreichen Absenden verbraucht.
 * - Alle Schritte sitzen hinter einer Datenbank-Sperre. Zwei gleichzeitige Anfragen können
 *   deshalb nie zwei Admins erzeugen, auch nicht mit zwei verschiedenen gültigen Links.
 * - Sobald ein Admin existiert, antwortet jeder Setup-Aufruf wie ein ungültiger Link.
 */
@Service
public class SetupTokenService {

    /** Beliebige feste Zahl, dient nur als Name der Sperre. */
    private static final long LOCK_ID = 4_711_001L;

    /** Der gültige Link samt dem TOTP-Geheimnis, das der Setup-Seite schon gezeigt wurde. */
    public record ValidToken(UUID id, byte[] pendingTotpEnc) {
    }

    private final JdbcClient jdbc;
    private final AppProperties props;

    public SetupTokenService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
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
        loadValid(token);
    }

    /**
     * Sperrt die Einrichtung für andere Transaktionen, prüft den Link und sperrt seine Zeile.
     * Ungültig, abgelaufen, benutzt oder Admin schon vorhanden: immer dieselbe Antwort.
     * Muss in einer Transaktion des Aufrufers laufen.
     */
    ValidToken loadValid(String token) {
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
                .query((rs, n) -> new ValidToken(rs.getObject("id", UUID.class), rs.getBytes("pending_totp_enc")))
                .optional()
                .orElseThrow(SetupTokenService::invalidLink);
    }

    void storePendingTotp(UUID tokenId, byte[] encryptedSecret) {
        jdbc.sql("UPDATE setup_tokens SET pending_totp_enc = :enc WHERE id = :id")
                .param("enc", encryptedSecret).param("id", tokenId).update();
    }

    /** Verbraucht den Link und entfernt das Zwischengeheimnis. */
    void markUsed(UUID tokenId) {
        jdbc.sql("UPDATE setup_tokens SET used_at = now(), pending_totp_enc = NULL WHERE id = :id")
                .param("id", tokenId).update();
    }

    /** Transaktionsweite Sperre, wird beim Ende der Transaktion automatisch freigegeben. */
    private void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:id)").param("id", LOCK_ID).query().singleRow();
    }

    private static ApiException invalidLink() {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
    }
}
