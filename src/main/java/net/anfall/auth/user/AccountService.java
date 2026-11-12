package net.anfall.auth.user;

import java.util.Optional;
import java.util.UUID;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Konten anlegen und nachschlagen. Gemeinsam genutzt vom Setup, später von der Registrierung (Etappe 5). */
@Service
public class AccountService {

    /** Was der Login über ein Konto wissen muss. */
    public record LoginData(UUID id, String username, String language, boolean banned, boolean emailVerified,
                            String passwordHash, boolean hasTotp) {
    }

    private final JdbcClient jdbc;
    private final PasswordService passwords;

    public AccountService(JdbcClient jdbc, PasswordService passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    /** Geprüfte und normalisierte Eingaben für ein neues Konto. */
    public record NewAccount(String username, String email, String password, String language) {
    }

    /**
     * Prüft die Eingaben und gibt sie normalisiert zurück (Username und E-Mail klein geschrieben).
     * Wirft einen ApiException mit Fehlercode, wenn etwas nicht passt oder schon vergeben ist.
     */
    public NewAccount validate(String username, String email, String password, String language) {
        String u = AccountFields.normalizeUsername(username);
        if (!AccountFields.isValidUsername(u)) {
            throw bad("username_invalid");
        }
        String e = AccountFields.normalizeEmail(email);
        if (!AccountFields.isValidEmail(e)) {
            throw bad("email_invalid");
        }
        if (!AccountFields.isValidLanguage(language)) {
            throw bad("language_invalid");
        }
        String passwordError = passwords.check(password);
        if (passwordError != null) {
            throw bad(passwordError);
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :v)").param("v", u)
                .query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_pii WHERE email = :v)").param("v", e)
                .query(Boolean.class).single()) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken");
        }
        return new NewAccount(u, e, password, language);
    }

    /** Legt Konto, Kontaktdaten und Zugangsdaten an. Die Eingaben müssen schon validiert sein. */
    public UUID insert(NewAccount a, boolean emailVerified, UUID invitedBy) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, username, language, invited_by) VALUES (:id, :u, :l, :inv)")
                .param("id", id).param("u", a.username()).param("l", a.language()).param("inv", invitedBy).update();
        jdbc.sql("INSERT INTO user_pii (user_id, email, email_verified) VALUES (:id, :e, :v)")
                .param("id", id).param("e", a.email()).param("v", emailVerified).update();
        jdbc.sql("INSERT INTO user_credentials (user_id, password_hash) VALUES (:id, :h)")
                .param("id", id).param("h", passwords.hash(a.password())).update();
        return id;
    }

    /** Sucht per Username oder E-Mail (beides schon normalisiert). */
    public Optional<LoginData> findForLogin(String identifier) {
        return jdbc.sql("""
                        SELECT u.id, u.username, u.language, u.banned, p.email_verified, c.password_hash,
                               EXISTS (SELECT 1 FROM user_totp t WHERE t.user_id = u.id) AS has_totp
                        FROM users u
                        JOIN user_pii p ON p.user_id = u.id
                        JOIN user_credentials c ON c.user_id = u.id
                        WHERE u.username = :i OR p.email = :i
                        """)
                .param("i", identifier)
                .query((rs, n) -> new LoginData(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("language"), rs.getBoolean("banned"), rs.getBoolean("email_verified"),
                        rs.getString("password_hash"), rs.getBoolean("has_totp")))
                .optional();
    }

    public void setPassword(UUID userId, String newPassword) {
        jdbc.sql("UPDATE user_credentials SET password_hash = :h, changed_at = now() WHERE user_id = :u")
                .param("h", passwords.hash(newPassword)).param("u", userId).update();
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }
}
