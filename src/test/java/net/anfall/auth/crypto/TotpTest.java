package net.anfall.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TotpTest {

    // Testvektoren aus RFC 6238 Anhang B (SHA-1). Dort 8 Stellen, wir nutzen die letzten 6.
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesRfc6238TestVectors() {
        assertThat(code(59L)).isEqualTo("287082");
        assertThat(code(1111111109L)).isEqualTo("081804");
        assertThat(code(1111111111L)).isEqualTo("050471");
        assertThat(code(1234567890L)).isEqualTo("005924");
        assertThat(code(2000000000L)).isEqualTo("279037");
        assertThat(code(20000000000L)).isEqualTo("353130");
    }

    private static String code(long epochSeconds) {
        return Totp.codeForStep(RFC_SECRET, Totp.stepAt(epochSeconds * 1000));
    }

    @Test
    void acceptsCurrentAndNeighbourStepsButNotFurther() {
        long now = 1_700_000_000_000L;
        long step = Totp.stepAt(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeForStep(RFC_SECRET, step), now, null)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeForStep(RFC_SECRET, step - 1), now, null)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeForStep(RFC_SECRET, step + 1), now, null)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.codeForStep(RFC_SECRET, step - 2), now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.codeForStep(RFC_SECRET, step + 2), now, null)).isEmpty();
    }

    @Test
    void rejectsReplayOfAnAlreadyUsedStep() {
        long now = 1_700_000_000_000L;
        long step = Totp.stepAt(now);
        String code = Totp.codeForStep(RFC_SECRET, step);
        OptionalLong first = Totp.verify(RFC_SECRET, code, now, null);
        assertThat(first).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, code, now, first.getAsLong())).isEmpty();
    }

    @Test
    void rejectsMalformedInput() {
        long now = 1_700_000_000_000L;
        assertThat(Totp.verify(RFC_SECRET, null, now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "", now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "1234567", now, null)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, null)).isEmpty();
    }

    @Test
    void base32MatchesRfc4648Vectors() {
        assertThat(Totp.toBase32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.toBase32("fo".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXQ");
        assertThat(Totp.toBase32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        // Das RFC-Testgeheimnis, so wie es Authenticator-Apps kennen
        assertThat(Totp.toBase32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void newSecretsAreRandomAnd160Bit() {
        byte[] a = Totp.newSecret();
        assertThat(a).hasSize(20);
        assertThat(a).isNotEqualTo(Totp.newSecret());
    }
}
