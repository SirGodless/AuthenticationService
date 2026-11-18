package net.anfall.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void codesHaveAtLeast112BitsBecauseNistDemandsSaltedSlowHashingBelowThat() {
        // 24 Zeichen aus 32 Symbolen: 24 * 5 = 120 Bit
        assertThat(RecoveryCodes.generate().get(0).replace("-", "")).hasSize(24);
    }

    @Test
    void generatesTenUniqueCodesInExpectedFormat() {
        var codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("([A-HJ-NP-Z2-9]{4}-){5}[A-HJ-NP-Z2-9]{4}"));
    }

    @Test
    void hashIsTolerantAgainstCaseDashesAndSpaces() {
        String hash = RecoveryCodes.hash("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ");
        assertThat(RecoveryCodes.hash("abcd efgh jklm npqr stuv wxyz")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCDEFGHJKLMNPQRSTUVWXYZ")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCD-EFGH-JKLM-NPQR-STUV-WXY2")).isNotEqualTo(hash);
    }
}
