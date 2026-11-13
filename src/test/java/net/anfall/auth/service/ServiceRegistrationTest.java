package net.anfall.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

class ServiceRegistrationTest extends IntegrationTestBase {

    @Autowired ServiceRegistrationService registration;
    @Autowired ServiceRepository services;
    @Autowired RegisteredClientRepository clients;
    @Autowired PasswordEncoder encoder;

    @Test
    void registeringCreatesPermissionClientAndHubEntryInOneStep() {
        var registered = registration.register("vault", "Vaultwarden", List.of("https://vault.example/cb"));

        assertThat(registered.service().requiredPermission()).isEqualTo("service.vault");
        assertThat(services.findByKey("vault")).get().extracting(ServiceInfo::displayName, ServiceInfo::openMode)
                .containsExactly("Vaultwarden", "new_tab");           // zuerst nur weiterleiten
        assertThat(jdbc.sql("SELECT type, builtin FROM permissions WHERE key = 'service.vault'").query().singleRow())
                .containsEntry("type", "boolean").containsEntry("builtin", false);

        var client = clients.findByClientId("vault");
        assertThat(client.getRedirectUris()).containsExactly("https://vault.example/cb");
        assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email", "offline_access");
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive().toMinutes()).isEqualTo(10);
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive().toDays()).isEqualTo(30);
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();    // Rotation
    }

    @Test
    void theClientSecretIsOnlyStoredAsHash() {
        var registered = registration.register("vault", "Vaultwarden", List.of("https://vault.example/cb"));
        String stored = clients.findByClientId("vault").getClientSecret();
        assertThat(stored).isNotEqualTo(registered.clientSecret()).startsWith("$argon2id$");
        assertThat(encoder.matches(registered.clientSecret(), stored)).isTrue();
        assertThat(registered.clientSecret()).hasSize(43);   // 256 Bit
    }

    @Test
    void invalidAndDuplicateRegistrationsAreRejected() {
        registration.register("vault", "Vaultwarden", List.of("https://vault.example/cb"));
        assertThatThrownBy(() -> registration.register("vault", "Nochmal", List.of("https://x.example/cb")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("service_exists"));
        assertThatThrownBy(() -> registration.register("Böse Zeichen!", "X", List.of("https://x.example/cb")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("service_key_invalid"));
        assertThatThrownBy(() -> registration.register("ohne-uri", "X", List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("service_invalid"));
        assertThat(services.findAll()).hasSize(1);
    }
}
