package net.anfall.auth.admincli;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RotateSigningKeyCommandTest extends IntegrationTestBase {

    @Autowired RotateSigningKeyCommand command;

    @Test
    void rotatingMakesANewKeyActiveAndKeepsTheOldOneForVerification() {
        String before = jdbc.sql("SELECT kid FROM oidc_signing_keys WHERE retired_at IS NULL").query(String.class).single();
        int[] code = new int[1];

        String out = AdminCliTest.capture(command, List.of(), code);

        assertThat(code[0]).isZero();
        String after = jdbc.sql("SELECT kid FROM oidc_signing_keys WHERE retired_at IS NULL").query(String.class).single();
        assertThat(after).isNotEqualTo(before);
        assertThat(out).contains(after);
        assertThat(jdbc.sql("SELECT count(*) FROM oidc_signing_keys").query(Long.class).single()).isEqualTo(2);
    }
}
