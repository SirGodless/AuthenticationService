package net.anfall.auth.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Alle SQL-Zugriffe auf Konten. Personenbezogene Daten (E-Mail) und Zugangsdaten (Passwort) liegen in eigenen
 * Tabellen, getrennt von users (UUID, Username). Siehe docs/decisions/data-model.md.
 */
@Component
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean usernameExists(String username) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :v)").param("v", username)
                .query(Boolean.class).single();
    }

    public boolean emailExists(String email) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_pii WHERE email = :v)").param("v", email)
                .query(Boolean.class).single();
    }

    /** Legt Konto, Kontaktdaten und Zugangsdaten an. Die Eingaben müssen schon geprüft sein. */
    public UUID insert(String username, String email, String language, String passwordHash,
                       boolean emailVerified, UUID invitedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, username, language, invited_by) VALUES (:id, :u, :l, :inv)")
                .param("id", id).param("u", username).param("l", language).param("inv", invitedBy).update();
        jdbc.sql("INSERT INTO user_pii (user_id, email, email_verified) VALUES (:id, :e, :v)")
                .param("id", id).param("e", email).param("v", emailVerified).update();
        jdbc.sql("INSERT INTO user_credentials (user_id, password_hash) VALUES (:id, :h)")
                .param("id", id).param("h", passwordHash).update();
        return id;
    }

    /** Sucht per Username oder E-Mail (beides schon normalisiert). */
    public Optional<LoginAccount> findForLogin(String identifier) {
        return jdbc.sql("""
                        SELECT u.id, u.username, u.language, u.banned, p.email_verified, c.password_hash,
                               EXISTS (SELECT 1 FROM user_totp t WHERE t.user_id = u.id) AS has_totp
                        FROM users u
                        JOIN user_pii p ON p.user_id = u.id
                        JOIN user_credentials c ON c.user_id = u.id
                        WHERE u.username = :i OR p.email = :i
                        """)
                .param("i", identifier)
                .query((rs, n) -> new LoginAccount(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("language"), rs.getBoolean("banned"), rs.getBoolean("email_verified"),
                        rs.getString("password_hash"), rs.getBoolean("has_totp")))
                .optional();
    }

    public Optional<AccountContact> findContactByEmail(String normalizedEmail) {
        return findContact("p.email = :v", normalizedEmail);
    }

    public Optional<AccountContact> findContactById(UUID id) {
        return findContact("u.id = :v", id);
    }

    private Optional<AccountContact> findContact(String where, Object value) {
        return jdbc.sql("""
                        SELECT u.id, u.username, p.email, u.language, p.email_verified, u.banned
                        FROM users u JOIN user_pii p ON p.user_id = u.id
                        WHERE\s""" + where)
                .param("v", value)
                .query((rs, n) -> new AccountContact(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("email"), rs.getString("language"), rs.getBoolean("email_verified"),
                        rs.getBoolean("banned")))
                .optional();
    }

    public String passwordHash(UUID userId) {
        return jdbc.sql("SELECT password_hash FROM user_credentials WHERE user_id = :u").param("u", userId)
                .query(String.class).single();
    }

    public void setPasswordHash(UUID userId, String passwordHash) {
        jdbc.sql("UPDATE user_credentials SET password_hash = :h, changed_at = now() WHERE user_id = :u")
                .param("h", passwordHash).param("u", userId).update();
    }

    public void markEmailVerified(UUID userId) {
        jdbc.sql("UPDATE user_pii SET email_verified = true WHERE user_id = :u").param("u", userId).update();
    }

    public String language(UUID userId) {
        return jdbc.sql("SELECT language FROM users WHERE id = :id").param("id", userId)
                .query(String.class).single();
    }
}
