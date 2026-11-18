package net.anfall.auth.twofactor;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.anfall.auth.crypto.Tokens;

/**
 * Einmalige Wiederherstellungscodes für die 2FA: 10 Stück, je 24 Zeichen mit 120 Bit Zufall,
 * Format XXXX-XXXX-XXXX-XXXX-XXXX-XXXX.
 *
 * Warum so lang: NIST SP 800-63B-4 (3.1.2.2) verlangt für Look-up-Secrets unter 112 Bit ein gesalzenes
 * Passwort-Hashing. Ab 112 Bit genügt ein normaler Hash (SHA-256), weil sich 120 Bit Zufall nicht durchprobieren lassen.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final int CODE_LENGTH = 24; // 24 Zeichen mal 5 Bit = 120 Bit
    // 32 Zeichen ohne 0, O, 1, I (Verwechslungsgefahr), also 5 Bit pro Zeichen
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < CODE_LENGTH; c++) {
                if (c > 0 && c % 4 == 0) {
                    sb.append('-');
                }
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /** Eingabe tolerant normalisieren (Groß/Klein, Bindestriche, Leerzeichen), dann hashen. */
    public static String hash(String code) {
        String normalized = code.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return Tokens.sha256Hex(normalized);
    }
}
