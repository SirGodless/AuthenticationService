package net.anfall.auth.account;

import java.util.UUID;
import net.anfall.auth.password.PasswordService;
import net.anfall.auth.password.check.PasswordContext;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Konten anlegen und Eingaben prüfen. Gemeinsam genutzt vom Setup, später von der Registrierung (Etappe 5). */
@Service
public class AccountService {

    /** Geprüfte und normalisierte Eingaben für ein neues Konto. */
    public record NewAccount(String username, String email, String password, String language) {
    }

    private final AccountRepository accounts;
    private final PasswordService passwords;

    public AccountService(AccountRepository accounts, PasswordService passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    /**
     * Prüft die Eingaben und gibt sie normalisiert zurück (Username und E-Mail klein geschrieben).
     * Wirft einen ApiException mit Fehlercode, wenn etwas nicht passt oder schon vergeben ist.
     */
    public NewAccount validate(String username, String email, String password, String language) {
        String u = AccountInputRules.normalizeUsername(username);
        if (!AccountInputRules.isValidUsername(u)) {
            throw bad("username_invalid");
        }
        String e = AccountInputRules.normalizeEmail(email);
        if (!AccountInputRules.isValidEmail(e)) {
            throw bad("email_invalid");
        }
        if (!AccountInputRules.isValidLanguage(language)) {
            throw bad("language_invalid");
        }
        String passwordError = passwords.check(password, PasswordContext.of(u, e));
        if (passwordError != null) {
            throw bad(passwordError);
        }
        if (accounts.usernameExists(u)) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken");
        }
        if (accounts.emailExists(e)) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken");
        }
        return new NewAccount(u, e, password, language);
    }

    /** Legt das Konto an. Die Eingaben müssen schon über validate() geprüft sein. */
    public UUID create(NewAccount account, boolean emailVerified, UUID invitedBy) {
        return accounts.insert(account.username(), account.email(), account.language(),
                passwords.hash(account.password()), emailVerified, invitedBy);
    }

    public void changePassword(UUID userId, String newPassword) {
        accounts.setPasswordHash(userId, passwords.hash(newPassword));
    }

    private static ApiException bad(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }
}
