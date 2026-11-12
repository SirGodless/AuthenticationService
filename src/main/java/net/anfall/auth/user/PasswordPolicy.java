package net.anfall.auth.user;

/**
 * Passwortregeln nach NIST SP 800-63B-4: mindestens 15, höchstens 128 Zeichen, keine Zusammensetzungsregeln.
 * Die Sperrliste bekannter Passwörter kommt in Etappe 2 (austauschbare Schnittstelle).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    /** @return Fehlercode oder null, wenn das Passwort passt */
    public static String check(String password) {
        if (password == null) {
            return "password_too_short";
        }
        // Zeichen zählen, nicht UTF-16-Einheiten (sonst zählt ein Emoji doppelt)
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return "password_too_short";
        }
        if (length > MAX_LENGTH) {
            return "password_too_long";
        }
        return null;
    }
}
