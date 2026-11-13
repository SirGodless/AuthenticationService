package net.anfall.auth.password;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.SecretCipher;
import net.anfall.auth.password.check.PasswordBlocklist;
import net.anfall.auth.password.check.PasswordContext;
import net.anfall.auth.password.check.PasswordLength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Alles rund ums Passwort an einer Stelle: Regeln, Sperrliste, Hashing, Prüfen.
 * Umsetzung von NIST SP 800-63B-4, Abschnitt 3.1.1.2, siehe docs/NIST-800-63B-4.md.
 *
 * Gespeichert wird "v1:" plus der mit dem Pepper-Schlüssel verschlüsselte Argon2id-Hash (NIST: zusätzlicher Schritt mit
 * einem Schlüssel, den nur der Dienst kennt und der getrennt von den Hashes liegt). Ein gestohlener Datenbank-Dump allein
 * lässt sich so nicht offline knacken.
 *
 * Passwörter werden vor allem anderen Unicode-normalisiert (NFC, wie NIST es dort empfiehlt), damit dasselbe Passwort
 * von verschiedenen Tastaturen und Geräten gleich behandelt wird. Sonst wird nichts verändert (kein Kürzen, kein Trimmen).
 */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);
    private static final String PEPPER_PREFIX = "v1:";

    private final PasswordEncoder encoder;
    private final PasswordBlocklist blocklist;
    private final SecretCipher pepper;
    /** Wörter, die zum Dienst gehören (Name, Adresse), damit niemand "anfall.net" als Passwort wählt. */
    private final List<String> serviceWords;
    /** Zum Angleichen der Antwortzeit, wenn der Account nicht existiert (verhindert Rückschlüsse über die Zeit). */
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder, PasswordBlocklist blocklist, AppProperties props) {
        this.encoder = encoder;
        this.blocklist = blocklist;
        this.pepper = new SecretCipher(props.passwordPepperKey(), "PASSWORD_PEPPER_KEY");
        this.serviceWords = serviceWords(props);
        this.dummyHash = hash("dummy-password-for-timing");
    }

    /**
     * Prüft ein neues Passwort (anlegen, ändern, zurücksetzen).
     *
     * @param context Wörter aus dem Konto (Username, E-Mail), siehe {@link PasswordContext}
     * @return Fehlercode oder null, wenn das Passwort die Regeln erfüllt
     */
    public String check(String password, PasswordContext context) {
        String normalized = normalize(password);
        String lengthError = PasswordLength.check(normalized);
        if (lengthError != null) {
            return lengthError;
        }
        if (blocklist.isBlocked(normalized)) {
            return "password_blocked";
        }
        if (context.plus(serviceWords).isMadeOfContextWords(normalized)) {
            return "password_context";
        }
        return null;
    }

    public String hash(String password) {
        byte[] argon2Hash = encoder.encode(normalize(password)).getBytes(StandardCharsets.UTF_8);
        return PEPPER_PREFIX + Base64.getEncoder().encodeToString(pepper.encrypt(argon2Hash));
    }

    public boolean matches(String password, String storedHash) {
        if (storedHash == null || !storedHash.startsWith(PEPPER_PREFIX)) {
            return false;
        }
        String argon2Hash;
        try {
            byte[] encrypted = Base64.getDecoder().decode(storedHash.substring(PEPPER_PREFIX.length()));
            argon2Hash = new String(pepper.decrypt(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Falscher Pepper-Schlüssel oder beschädigter Hash: laut im Log, aber nie mit Details zum Passwort
            log.error("Passwort-Hash nicht lesbar (falscher PASSWORD_PEPPER_KEY oder beschädigter Eintrag)");
            return false;
        }
        return encoder.matches(normalize(password), argon2Hash);
    }

    /** Rechnet genauso lange wie ein echter Vergleich, Ergebnis ist immer falsch. */
    public void dummyMatch(String password) {
        matches(password, dummyHash);
    }

    static String normalize(String password) {
        return password == null ? "" : Normalizer.normalize(password, Normalizer.Form.NFC);
    }

    private static List<String> serviceWords(AppProperties props) {
        List<String> words = new ArrayList<>();
        words.add(props.totpIssuer());           // z. B. "anfall.net"
        words.add("userhub");
        try {
            String host = URI.create(props.publicBaseUrl()).getHost();   // z. B. "auth.anfall.net"
            if (host != null) {
                words.add(host);
            }
        } catch (IllegalArgumentException ignored) {
            // Die Adresse wird beim Start ohnehin geprüft
        }
        String issuer = props.totpIssuer();
        if (issuer != null && issuer.contains(".")) {
            words.add(issuer.substring(0, issuer.indexOf('.')));   // "anfall"
        }
        return words;
    }
}
