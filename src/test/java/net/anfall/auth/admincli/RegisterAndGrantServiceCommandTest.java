package net.anfall.auth.admincli;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class RegisterAndGrantServiceCommandTest extends IntegrationTestBase {

    @Autowired RegisterServiceCommand register;
    @Autowired GrantServiceCommand grant;
    @Autowired PermissionService permissions;
    @Autowired RegisteredClientRepository clients;

    @Test
    void registerPrintsClientIdAndSecretOnceAndCreatesTheService() {
        int[] code = new int[1];
        String out = AdminCliTest.capture(register,
                List.of("vault", "Vaultwarden", "https://vault.example/cb,https://vault.example/other"), code);

        assertThat(code[0]).isZero();
        assertThat(out).contains("Client-ID:      vault").contains("Client-Secret:").contains("service.vault");
        assertThat(clients.findByClientId("vault").getRedirectUris())
                .containsExactlyInAnyOrder("https://vault.example/cb", "https://vault.example/other");
        // Das ausgegebene Geheimnis passt zum gespeicherten Hash
        String secret = out.lines().filter(l -> l.contains("Client-Secret:")).findFirst().orElseThrow()
                .replaceAll(".*Client-Secret:\\s+(\\S+).*", "$1");
        assertThat(secret).hasSize(43);
    }

    @Test
    void registerRejectsWrongUsageAndDuplicates() {
        int[] code = new int[1];
        AdminCliTest.capture(register, List.of("nur-ein-argument"), code);
        assertThat(code[0]).isEqualTo(2);
        AdminCliTest.capture(register, List.of("vault", "V", "https://a.example/cb"), code);
        AdminCliTest.capture(register, List.of("vault", "V", "https://a.example/cb"), code);
        assertThat(code[0]).isEqualTo(1);
    }

    @Test
    void grantGivesTheUserThePermissionOfTheService() {
        AdminCliTest.capture(register, List.of("vault", "Vaultwarden", "https://vault.example/cb"), new int[1]);
        UUID anna = createUser("anna", "anna@example.com", PASSWORD, true);
        int[] code = new int[1];

        String out = AdminCliTest.capture(grant, List.of("Anna", "vault"), code);

        assertThat(code[0]).isZero();
        assertThat(out).contains("anna").contains("Vaultwarden");
        assertThat(permissions.has(anna, "service.vault")).isTrue();
        assertThat(jdbc.sql("SELECT ip FROM audit_log WHERE action = 'permission.granted'").query(String.class).single())
                .isEqualTo("cli");
    }

    @Test
    void grantFailsForUnknownUserOrService() {
        AdminCliTest.capture(register, List.of("vault", "Vaultwarden", "https://vault.example/cb"), new int[1]);
        createUser("anna", "anna@example.com", PASSWORD, true);
        int[] code = new int[1];
        AdminCliTest.capture(grant, List.of("niemand", "vault"), code);
        assertThat(code[0]).isEqualTo(1);
        AdminCliTest.capture(grant, List.of("anna", "gibtsnicht"), code);
        assertThat(code[0]).isEqualTo(1);
        AdminCliTest.capture(grant, List.of("anna"), code);
        assertThat(code[0]).isEqualTo(2);
    }
}
