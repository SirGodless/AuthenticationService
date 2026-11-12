package net.anfall.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void generatesTenUniqueCodesInExpectedFormat() {
        var codes = RecoveryCodes.generate();
        assertThat(codes).hasSize(10);
        assertThat(new HashSet<>(codes)).hasSize(10);
        assertThat(codes).allMatch(c -> c.matches("([A-HJ-NP-Z2-9]{4}-){3}[A-HJ-NP-Z2-9]{4}"));
    }

    @Test
    void hashIsTolerantAgainstCaseDashesAndSpaces() {
        String hash = RecoveryCodes.hash("ABCD-EFGH-JKLM-NPQR");
        assertThat(RecoveryCodes.hash("abcd efgh jklm npqr")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCDEFGHJKLMNPQR")).isEqualTo(hash);
        assertThat(RecoveryCodes.hash("ABCD-EFGH-JKLM-NPQS")).isNotEqualTo(hash);
    }
}
