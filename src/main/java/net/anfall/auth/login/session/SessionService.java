package net.anfall.auth.login.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.Tokens;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Anmeldesitzungen. Der Browser bekommt einen zufälligen Wert im Cookie, in der Datenbank steht nur
 * dessen Hash. "Host"-Präfix im Cookie-Namen: Der Browser akzeptiert ihn nur mit Secure, ohne Domain
 * und mit Pfad "/", das Cookie gilt also nur für auth.anfall.net selbst.
 */
@Service
public class SessionService {

    public static final String COOKIE_NAME = "__Host-sid";

    public record SessionInfo(UUID userId, String username, boolean pending, boolean remember, Instant expiresAt) {
    }

    public record Created(String token, ResponseCookie cookie) {
    }

    private final JdbcClient jdbc;
    private final AppProperties.Session config;

    public SessionService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.config = props.session();
    }

    /**
     * Neue Sitzung. pending=true: Passwort stimmt, die 2FA fehlt noch (kurz gültig, zählt nicht als angemeldet).
     * Beim Wechsel von pending zu angemeldet wird immer eine NEUE Sitzung erzeugt (gegen Session Fixation).
     */
    public Created create(UUID userId, boolean remember, boolean pending) {
        String token = Tokens.newToken();
        Duration ttl = pending ? config.pendingTtl() : remember ? config.rememberTtl() : config.ttl();
        jdbc.sql("""
                        INSERT INTO login_sessions (id_hash, user_id, expires_at, remember, second_factor_pending)
                        VALUES (:id, :u, now() + make_interval(secs => :ttl), :r, :p)
                        """)
                .param("id", Tokens.sha256Hex(token)).param("u", userId)
                .param("ttl", (double) ttl.toSeconds()).param("r", remember).param("p", pending)
                .update();
        // Mit "Eingeloggt bleiben" überlebt das Cookie das Schließen des Browsers, sonst nicht.
        boolean persistent = remember && !pending;
        return new Created(token, cookie(token, persistent ? ttl : null));
    }

    public Optional<SessionInfo> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT s.user_id, u.username, s.second_factor_pending, s.remember, s.expires_at
                        FROM login_sessions s JOIN users u ON u.id = s.user_id
                        WHERE s.id_hash = :id AND s.expires_at > now() AND NOT u.banned
                        """)
                .param("id", Tokens.sha256Hex(token))
                .query((rs, n) -> new SessionInfo(rs.getObject("user_id", UUID.class), rs.getString("username"),
                        rs.getBoolean("second_factor_pending"), rs.getBoolean("remember"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public void revoke(String token) {
        if (token != null) {
            jdbc.sql("DELETE FROM login_sessions WHERE id_hash = :id").param("id", Tokens.sha256Hex(token)).update();
        }
    }

    /** Beendet alle Sitzungen eines Users (Passwort-Reset, später Bann). */
    public int revokeAll(UUID userId) {
        return jdbc.sql("DELETE FROM login_sessions WHERE user_id = :u").param("u", userId).update();
    }

    /** Beendet alle Sitzungen außer der aktuellen (Passwort geändert). */
    public int revokeAllExcept(UUID userId, String keepToken) {
        return jdbc.sql("DELETE FROM login_sessions WHERE user_id = :u AND id_hash <> :keep")
                .param("u", userId).param("keep", Tokens.sha256Hex(keepToken == null ? "" : keepToken)).update();
    }

    public int deleteExpired() {
        return jdbc.sql("DELETE FROM login_sessions WHERE expires_at < now()").update();
    }

    public static String tokenFrom(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    public ResponseCookie clearCookie() {
        return cookie("", Duration.ZERO);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true).secure(true).sameSite("Lax").path("/");
        if (maxAge != null) {
            b.maxAge(maxAge);
        }
        return b.build();
    }
}
