package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import net.anfall.auth.service.ServiceRegistrationService;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Die Betreiber-Befehle (auth-admin) starten das Backend ohne Webserver. Dieser Test stellt sicher, dass der Kontext
 * dann vollständig aufgebaut wird, auch mit Provider und Diensten (hier ging es schon einmal schief).
 * Eigenständig, weil die Basisklasse der anderen Tests einen Webserver mit MockMvc voraussetzt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.main.web-application-type=none",
        "app.public-base-url=https://auth.example.test",
        "app.argon2.memory-kib=19456",
        "app.argon2.iterations=2",
        "app.totp-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.password-pepper-key=QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=",
        "app.oidc-key-encryption-key=Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M=",
        "app.password-blocklist-file=src/test/resources/blocklist-test.txt",
        "app.mail.async=false"
})
class NonWebContextTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestBase.POSTGRES::getPassword);
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> IntegrationTestBase.GREEN_MAIL.getSmtp().getPort());
    }

    @Autowired ServiceRegistrationService registration;

    @Test
    void contextStartsWithoutAWebServerAndServicesCanBeRegistered() {
        var registered = registration.register("cli-test", "CLI Test", java.util.List.of("https://cli.example/cb"));
        assertThat(registered.service().key()).isEqualTo("cli-test");
        assertThat(registered.clientSecret()).hasSize(43);
    }
}
