package net.anfall.auth.user;

import java.text.Normalizer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Alles rund ums Passwort an einer Stelle: Regeln, Sperrliste, Hashing, Prüfen.
 * Passwörter werden vor allem anderen Unicode-normalisiert (NFKC, wie NIST SP 800-63B empfiehlt),
 * damit dasselbe Passwort von verschiedenen Tastaturen und Geräten gleich behandelt wird.
 */
@Service
public class PasswordService {

    private final PasswordEncoder encoder;
    private final PasswordBlocklist blocklist;
    /** Zum Angleichen der Antwortzeit, wenn der Account nicht existiert (verhindert Rückschlüsse über die Zeit). */
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder, PasswordBlocklist blocklist) {
        this.encoder = encoder;
        this.blocklist = blocklist;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    /** @return Fehlercode oder null, wenn das Passwort die Regeln erfüllt */
    public String check(String password) {
        String error = PasswordPolicy.check(normalize(password));
        if (error != null) {
            return error;
        }
        return blocklist.isBlocked(normalize(password)) ? "password_blocked" : null;
    }

    public String hash(String password) {
        return encoder.encode(normalize(password));
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(normalize(password), hash);
    }

    /** Rechnet genauso lange wie ein echter Vergleich, Ergebnis ist immer falsch. */
    public void dummyMatch(String password) {
        encoder.matches(normalize(password), dummyHash);
    }

    static String normalize(String password) {
        return password == null ? "" : Normalizer.normalize(password, Normalizer.Form.NFKC);
    }
}
