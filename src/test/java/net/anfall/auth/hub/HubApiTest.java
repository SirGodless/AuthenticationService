package net.anfall.auth.hub;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import net.anfall.auth.service.ServiceHealthService;
import net.anfall.auth.service.ServiceRegistrationService;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Hub-Daten: nur Dienste mit Recht, Farbe und Status je Dienst, Mailstatus nur für Admins. */
class HubApiTest extends AdminTestBase {

    @Autowired ServiceRegistrationService registration;
    @Autowired ServiceHealthService health;

    private void service(String key, String name, String color) {
        registration.register(key, name, List.of("https://" + key + ".example/cb"));
        jdbc.sql("UPDATE services SET accent_color = :c, url = :u, description = :d WHERE key = :k")
                .param("c", color).param("u", "https://" + key + ".example").param("d", "Beschreibung " + name).param("k", key).update();
    }

    private JsonNode hub(Browser b) throws Exception {
        Browser.Result r = b.get("/api/hub");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body());
    }

    @Test
    void requiresALogin() throws Exception {
        assertThat(browser().get("/api/hub").status()).isEqualTo(401);
    }

    @Test
    void aUserSeesOnlyTheServicesTheyMayUse() throws Exception {
        service("vault", "Vaultwarden", "#2f81f7");
        service("abs", "Audiobookshelf", null);
        Session anna = loggedInWithPermissions("anna", false, "service.vault");
        JsonNode services = hub(anna.browser()).get("services");
        assertThat(services).hasSize(1);
        assertThat(services.get(0).get("key").asString()).isEqualTo("vault");
        assertThat(services.get(0).get("name").asString()).isEqualTo("Vaultwarden");
        assertThat(services.get(0).get("accentColor").asString()).isEqualTo("#2f81f7");
        assertThat(services.get(0).get("url").asString()).isEqualTo("https://vault.example");
        assertThat(services.get(0).get("openMode").asString()).isEqualTo("new_tab");
        // Ohne Recht ist die Liste leer
        assertThat(hub(loggedInWithPermissions("bob", false).browser()).get("services")).isEmpty();
    }

    @Test
    void theRightIsDecidedLiveSoRevokingHidesTheService() throws Exception {
        service("vault", "Vaultwarden", null);
        Session anna = loggedInWithPermissions("anna", false, "service.vault");
        assertThat(hub(anna.browser()).get("services")).hasSize(1);
        jdbc.sql("DELETE FROM user_permissions WHERE user_id = :u").param("u", anna.id()).update();
        assertThat(hub(anna.browser()).get("services")).isEmpty();
    }

    @Test
    void theAdminRoleAloneGivesNoServices() throws Exception {
        service("vault", "Vaultwarden", null);
        Session admin = loggedInAdmin("joshua");
        assertThat(hub(admin.browser()).get("services")).isEmpty();
    }

    @Test
    void statusIsShownPerService() throws Exception {
        service("vault", "Vaultwarden", null);
        Session anna = loggedInWithPermissions("anna", false, "service.vault");
        assertThat(hub(anna.browser()).get("services").get(0).get("status").asString()).isEqualTo("unknown");
        jdbc.sql("UPDATE services SET health_url = 'http://127.0.0.1:1/x' WHERE key = 'vault'").update();
        health.checkNow();
        assertThat(hub(anna.browser()).get("services").get(0).get("status").asString()).isEqualTo("down");
    }

    @Test
    void theMailStatusIsOnlyForAdmins() throws Exception {
        health.checkNow();
        assertThat(hub(loggedInWithPermissions("anna", false).browser()).get("mail").isNull()).isTrue();
        Session admin = loggedInAdmin("joshua");
        assertThat(hub(admin.browser()).get("mail").get("status").asString()).isEqualTo("up");
    }

    @Test
    void accountDataForTheHub() throws Exception {
        Session anna = loggedInWithPermissions("anna", false, "links.create");
        giveNumber(anna.id(), "links.max_active", 5);
        JsonNode account = hub(anna.browser()).get("account");
        assertThat(account.get("username").asString()).isEqualTo("anna");
        assertThat(account.get("language").asString()).isEqualTo("en");
        assertThat(account.get("twoFactorEnabled").asBoolean()).isFalse();
        assertThat(account.get("isAdmin").asBoolean()).isFalse();
        assertThat(account.get("canCreateLinks").asBoolean()).isTrue();
        assertThat(account.get("activeLinks").asLong()).isZero();
        assertThat(account.get("maxActiveLinks").get("number").asLong()).isEqualTo(5);
        Session admin = loggedInAdmin("joshua");
        assertThat(hub(admin.browser()).get("account").get("isAdmin").asBoolean()).isTrue();
        assertThat(hub(admin.browser()).get("account").get("twoFactorEnabled").asBoolean()).isTrue();
    }

    private void giveNumber(UUID user, String key, long n) {
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key, number_value) VALUES (:u, :k, :n)").param("u", user).param("k", key).param("n", n).update();
    }
}
