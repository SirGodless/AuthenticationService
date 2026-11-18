package net.anfall.auth.password.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kontextbezogene Wörter für die Sperrliste (NIST SP 800-63B-4, 3.1.1.2: "context-specific words, such as the name of
 * the service, the username, and derivatives thereof"). Verglichen wird das GANZE Passwort, keine Teile davon:
 * Gesperrt ist ein Passwort nur, wenn es aus nichts anderem besteht als einem dieser Wörter, auch mehrfach hintereinander
 * und mit Ziffern, Trennzeichen oder anderer Groß- und Kleinschreibung dazwischen ("Joshua-Joshua-2026" bei Username
 * "joshua"). Ein Passwort, das den Usernamen nur enthält, bleibt erlaubt.
 */
public record PasswordContext(List<String> words) {

    public static final PasswordContext NONE = new PasswordContext(List.of());
    // Kürzere Wörter wären kein sinnvoller Vergleich (bei 15 Zeichen Mindestlänge ohnehin nur als Wiederholung denkbar)
    private static final int MIN_WORD_LETTERS = 3;

    /** Wörter aus den Kontodaten: Username, ganze E-Mail-Adresse und der Teil vor dem @. */
    public static PasswordContext of(String username, String email) {
        List<String> words = new ArrayList<>();
        words.add(username);
        words.add(email);
        if (email != null && email.contains("@")) {
            words.add(email.substring(0, email.indexOf('@')));
        }
        return new PasswordContext(words);
    }

    public PasswordContext plus(List<String> more) {
        List<String> all = new ArrayList<>(words);
        all.addAll(more);
        return new PasswordContext(all);
    }

    public boolean isMadeOfContextWords(String password) {
        String letters = lettersOnly(password);
        if (letters.isEmpty()) {
            return false;
        }
        for (String word : words) {
            String w = lettersOnly(word);
            if (w.length() >= MIN_WORD_LETTERS && letters.length() % w.length() == 0
                    && letters.equals(w.repeat(letters.length() / w.length()))) {
                return true;
            }
        }
        return false;
    }

    private static String lettersOnly(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        s.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetter).forEach(sb::appendCodePoint);
        return sb.toString();
    }
}
