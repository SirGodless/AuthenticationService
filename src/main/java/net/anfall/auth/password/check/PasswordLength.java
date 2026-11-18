package net.anfall.auth.password.check;

/**
 * Längenregel nach NIST SP 800-63B-4 (Abschnitt 3.1.1.2): mindestens 15 Zeichen für ein Passwort als einziger Faktor,
 * mindestens 64 müssen erlaubt sein (hier 128). Jeder Unicode-Codepoint zählt als ein Zeichen.
 * Keine weiteren Zusammensetzungsregeln (die verbietet NIST ausdrücklich).
 */
public final class PasswordLength {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;

    private PasswordLength() {
    }

    /** @return Fehlercode oder null, wenn die Länge passt */
    public static String check(String password) {
        if (password == null) {
            return "password_too_short";
        }
        // Codepoints zählen, nicht UTF-16-Einheiten (sonst zählt ein Emoji doppelt)
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
