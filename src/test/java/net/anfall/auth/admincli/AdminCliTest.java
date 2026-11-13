package net.anfall.auth.admincli;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/** Der Verteiler der Betreiber-Befehle. Die einzelnen Befehle haben eigene Tests. */
class AdminCliTest extends IntegrationTestBase {

    @Autowired ConfigurableApplicationContext context;
    @Autowired List<AdminCommand> commands;

    @Test
    void allCommandsAreRegisteredUnderTheirNames() {
        assertThat(commands).extracting(AdminCommand::name)
                .containsExactlyInAnyOrder("reset-2fa", "register-service", "grant-service", "rotate-signing-key");
    }

    @Test
    void dispatcherRunsTheNamedCommandAndRejectsUnknownOnes() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        AdminCli cli = new AdminCli(context, commands);

        assertThat(cli.execute(List.of("irgendwas", "joshua"))).isEqualTo(2);
        assertThat(cli.execute(List.of())).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp").query(Long.class).single()).isEqualTo(1);

        assertThat(cli.execute(List.of("reset-2fa", "joshua"))).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp").query(Long.class).single()).isZero();
    }

    /** Fängt die Ausgabe eines Befehls auf. */
    static String capture(AdminCommand command, List<String> args, int[] exitCode) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        exitCode[0] = command.run(args, new PrintStream(buffer));
        return buffer.toString();
    }
}
