package net.anfall.auth.twofactor;

import java.util.OptionalLong;
import java.util.UUID;
import net.anfall.auth.crypto.SecretCipher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Prüft die Codes des zweiten Faktors beim Login: TOTP-Code aus der App oder einmaliger Wiederherstellungscode. */
@Component
public class SecondFactorChecker {

    private record TotpRow(byte[] secretEnc, Long lastUsedStep) {
    }

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public SecondFactorChecker(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Ein 6-stelliger Code ist ein TOTP-Code, alles andere gilt als Wiederherstellungscode. */
    public static boolean looksLikeTotpCode(String code) {
        return code.matches("\\d{6}");
    }

    /** Jeder 30-Sekunden-Schritt gilt nur einmal, auch bei gleichzeitigen Anfragen. */
    public boolean verifyTotp(UUID userId, String code) {
        var row = jdbc.sql("SELECT secret_enc, last_used_step FROM user_totp WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> new TotpRow(rs.getBytes("secret_enc"), rs.getObject("last_used_step", Long.class)))
                .optional();
        if (row.isEmpty()) {
            return false;
        }
        byte[] secret = cipher.decrypt(row.get().secretEnc());
        OptionalLong step = Totp.verify(secret, code, System.currentTimeMillis(), row.get().lastUsedStep());
        if (step.isEmpty()) {
            return false;
        }
        // Bedingtes UPDATE: Zwei gleichzeitige Anfragen mit demselben Code, nur eine gewinnt (kein Wiederverwenden).
        return jdbc.sql("""
                        UPDATE user_totp SET last_used_step = :s
                        WHERE user_id = :u AND (last_used_step IS NULL OR last_used_step < :s)
                        """)
                .param("s", step.getAsLong()).param("u", userId).update() == 1;
    }

    /** Verbraucht den Wiederherstellungscode. Er funktioniert nur einmal. */
    public boolean useRecoveryCode(UUID userId, String code) {
        return jdbc.sql("""
                        UPDATE recovery_codes SET used_at = now()
                        WHERE user_id = :u AND code_hash = :h AND used_at IS NULL
                        """)
                .param("u", userId).param("h", RecoveryCodes.hash(code)).update() == 1;
    }
}
