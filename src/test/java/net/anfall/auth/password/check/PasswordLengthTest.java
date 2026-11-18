package net.anfall.auth.password.check;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** NIST SP 800-63B-4, 3.1.1.2: mindestens 15, mindestens 64 erlaubt, keine Zusammensetzungsregeln. */
class PasswordLengthTest {

    @Test
    void needs15CharsAndAllowsUpTo128WithoutCompositionRules() {
        assertThat(PasswordLength.check("a".repeat(14))).isEqualTo("password_too_short");
        assertThat(PasswordLength.check("a".repeat(15))).isNull();          // keine Zusammensetzungsregeln
        assertThat(PasswordLength.check("a".repeat(64))).isNull();          // 64 müssen erlaubt sein
        assertThat(PasswordLength.check("a".repeat(128))).isNull();
        assertThat(PasswordLength.check("a".repeat(129))).isEqualTo("password_too_long");
        assertThat(PasswordLength.check(null)).isEqualTo("password_too_short");
    }

    @Test
    void lengthCountsCodePointsNotUtf16Units() {
        // Ein Emoji ist 1 Zeichen, aber 2 UTF-16-Einheiten: 14 Emojis sind zu kurz, 15 reichen
        assertThat(PasswordLength.check("😀".repeat(14))).isEqualTo("password_too_short");
        assertThat(PasswordLength.check("😀".repeat(15))).isNull();
        assertThat(PasswordLength.check("äöü".repeat(5))).isNull();
    }

    @Test
    void spacesAndAllPrintableAsciiAreAccepted() {
        assertThat(PasswordLength.check("correct horse battery staple")).isNull();
        assertThat(PasswordLength.check("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ 0123456789")).isNull();
    }
}
