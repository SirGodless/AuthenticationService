package net.anfall.auth.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.anfall.auth.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Verschlüsselt TOTP-Geheimnisse mit AES-256-GCM. Der Schlüssel kommt aus der Umgebung. */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretCipher(AppProperties props) {
        this(props.totpEncryptionKey());
    }

    public SecretCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY ist kein gültiges Base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY muss genau 32 Byte (Base64) lang sein, "
                    + "erzeugen mit: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Ergebnis: IV (12 Byte) gefolgt vom Ciphertext mit Auth-Tag. */
    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschlüsselung fehlgeschlagen", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Entschlüsselung fehlgeschlagen (falscher Schlüssel?)", e);
        }
    }
}
