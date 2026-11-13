package net.anfall.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String KEY_B = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void roundTrip() {
        SecretCipher c = new SecretCipher(KEY_A, "TEST_KEY");
        byte[] plain = "geheim".getBytes();
        byte[] enc = c.encrypt(plain);
        assertThat(enc).isNotEqualTo(plain);
        assertThat(c.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void sameInputGivesDifferentCiphertext() {
        SecretCipher c = new SecretCipher(KEY_A, "TEST_KEY");
        assertThat(c.encrypt("x".getBytes())).isNotEqualTo(c.encrypt("x".getBytes()));
    }

    @Test
    void wrongKeyOrTamperedDataFails() {
        byte[] enc = new SecretCipher(KEY_A, "TEST_KEY").encrypt("geheim".getBytes());
        assertThatThrownBy(() -> new SecretCipher(KEY_B, "TEST_KEY").decrypt(enc)).isInstanceOf(IllegalStateException.class);
        enc[enc.length - 1] ^= 1;
        assertThatThrownBy(() -> new SecretCipher(KEY_A, "TEST_KEY").decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorMessageNamesTheSettingSoTheOperatorKnowsWhatToFix() {
        assertThatThrownBy(() -> new SecretCipher("", "PASSWORD_PEPPER_KEY")).hasMessageContaining("PASSWORD_PEPPER_KEY");
    }

    @Test
    void rejectsMissingOrWrongSizedKey() {
        assertThatThrownBy(() -> new SecretCipher(null, "TEST_KEY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("", "TEST_KEY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("kein base64!", "TEST_KEY")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16]), "TEST_KEY"))
                .isInstanceOf(IllegalStateException.class);
    }
}
