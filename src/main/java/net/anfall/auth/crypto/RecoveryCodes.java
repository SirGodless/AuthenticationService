package net.anfall.auth.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Einmalige Wiederherstellungscodes für die 2FA: 10 Stück, je 80 Bit Zufall, Format XXXX-XXXX-XXXX-XXXX. */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    // 32 Zeichen ohne 0, O, 1, I (Verwechslungsgefahr), also 5 Bit pro Zeichen
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 16; c++) {
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
