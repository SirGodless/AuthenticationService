package net.anfall.auth.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.anfall.auth.crypto.Tokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Einmal-Links per Mail. Der Token hat 256 Bit Zufall und steht nur als Hash in der Datenbank.
 * Verbraucht wird er mit einem einzigen UPDATE, deshalb kann ihn bei gleichzeitigen Anfragen genau eine einlösen.
 */
@Component
public class EmailTokens {

    public enum Purpose {
        CONFIRM_EMAIL("confirm_email"),
        PASSWORD_RESET("password_reset");

        final String db;

        Purpose(String db) {
            this.db = db;
        }
    }

    private final JdbcClient jdbc;

    public EmailTokens(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Neuer Token. Noch offene ältere Tokens desselben Zwecks für denselben User werden ungültig. */
    public String issue(UUID userId, Purpose purpose, Duration ttl) {
        jdbc.sql("UPDATE email_tokens SET used_at = now() WHERE user_id = :u AND purpose = :p AND used_at IS NULL")
                .param("u", userId).param("p", purpose.db).update();
        String token = Tokens.newToken();
        jdbc.sql("""
                        INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at)
                        VALUES (:id, :u, :p, :h, now() + make_interval(secs => :ttl))
                        """)
                .param("id", UUID.randomUUID()).param("u", userId).param("p", purpose.db)
                .param("h", Tokens.sha256Hex(token)).param("ttl", (double) ttl.toSeconds()).update();
        return token;
    }

    /** Ist der Token gültig? Verbraucht ihn nicht. */
    public boolean isValid(String token, Purpose purpose) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM email_tokens WHERE token_hash = :h AND purpose = :p
                                       AND used_at IS NULL AND expires_at > now())
                        """)
                .param("h", Tokens.sha256Hex(token == null ? "" : token)).param("p", purpose.db)
                .query(Boolean.class).single();
    }

    /** Verbraucht den Token und gibt den zugehörigen User zurück. Leer, wenn ungültig, abgelaufen oder benutzt. */
    public Optional<UUID> consume(String token, Purpose purpose) {
        return jdbc.sql("""
                        UPDATE email_tokens SET used_at = now()
                        WHERE token_hash = :h AND purpose = :p AND used_at IS NULL AND expires_at > now()
                        RETURNING user_id
                        """)
                .param("h", Tokens.sha256Hex(token == null ? "" : token)).param("p", purpose.db)
                .query(UUID.class).optional();
    }
}
