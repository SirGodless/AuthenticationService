package net.anfall.auth.account;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Prüfung und Normalisierung von Username, E-Mail und Sprache. */
public final class AccountInputRules {

    // a-z, 0-9, Punkt, Unterstrich, Bindestrich, 3 bis 32 Zeichen (gegen Lookalike-Zeichen)
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,32}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Set<String> LANGUAGES = Set.of("en", "de");

    private AccountInputRules() {
    }

    /** Username immer klein geschrieben prüfen und speichern. */
    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidUsername(String normalized) {
        return USERNAME.matcher(normalized).matches();
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValidEmail(String normalized) {
        return normalized.length() <= 254 && EMAIL.matcher(normalized).matches();
    }

    public static boolean isValidLanguage(String language) {
        return language != null && LANGUAGES.contains(language);
    }
}
