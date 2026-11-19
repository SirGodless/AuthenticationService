package net.anfall.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Dienste im Dashboard: Anzeige-Eigenschaften, Akzentfarbe je Dienst. */
class ServiceAdminApiTest extends AdminTestBase {

    @Autowired ServiceRegistrationService registration;

    private void vault() {
        registration.register("vault", "Vaultwarden", List.of("https://vault.example/cb"));
    }

    private static Map<String, Object> update(String name, String color) {
        Map<String, Object> m = new HashMap<>();
        m.put("displayName", name);
        m.put("description", "Passwörter");
        m.put("url", "https://vault.example");
        m.put("accentColor", color);
        m.put("accentLocked", false);
        m.put("healthUrl", "");
        return m;
    }

    @Test
    void anAdminSetsTheAccentColorAndTheDisplayData() throws Exception {
        vault();
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.patch("/api/admin/services/vault", update("Vaultwarden", "#2F81F7")).ok()).isTrue();
        JsonNode list = json.readTree(admin.get("/api/admin/services").body());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("accentColor").asString()).isEqualTo("#2f81f7");   // klein gespeichert
        assertThat(list.get(0).get("url").asString()).isEqualTo("https://vault.example");
        assertThat(list.get(0).get("description").asString()).isEqualTo("Passwörter");
        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE action = 'service.updated'").query(String.class).list()).hasSize(1);
    }

    @Test
    void anEmptyColorMeansNoOwnColor() throws Exception {
        vault();
        Browser admin = loggedInAdmin("joshua").browser();
        admin.patch("/api/admin/services/vault", update("Vaultwarden", "#2f81f7"));
        admin.patch("/api/admin/services/vault", update("Vaultwarden", ""));
        assertThat(json.readTree(admin.get("/api/admin/services").body()).get(0).get("accentColor").isNull()).isTrue();
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        vault();
        Browser admin = loggedInAdmin("joshua").browser();
        for (String bad : List.of("red", "#fff", "#12345g", "2f81f7", "#2f81f7;x", "url(x)")) {
            Browser.Result r = admin.patch("/api/admin/services/vault", update("Vaultwarden", bad));
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.body()).contains("service_color_invalid");
        }
        Map<String, Object> badUrl = update("Vaultwarden", "");
        badUrl.put("url", "javascript:alert(1)");
        assertThat(admin.patch("/api/admin/services/vault", badUrl).body()).contains("service_url_invalid");
        Map<String, Object> ftp = update("Vaultwarden", "");
        ftp.put("healthUrl", "ftp://x.example");
        assertThat(admin.patch("/api/admin/services/vault", ftp).body()).contains("service_health_url_invalid");
        assertThat(admin.patch("/api/admin/services/vault", update("  ", "")).body()).contains("service_name_invalid");
        assertThat(admin.patch("/api/admin/services/unknown", update("X", "")).status()).isEqualTo(404);
        // nichts davon hat etwas verändert
        assertThat(json.readTree(admin.get("/api/admin/services").body()).get(0).get("accentColor").isNull()).isTrue();
    }

    @Test
    void managingServicesNeedsItsRightAndTwoFactor() throws Exception {
        vault();
        assertThat(loggedInWithPermissions("anna", true, "admin.users.view").browser().get("/api/admin/services").status()).isEqualTo(403);
        assertThat(loggedInWithPermissions("bob", false, "admin.services.manage").browser().get("/api/admin/services").body()).contains("two_factor_required");
        Browser ok = loggedInWithPermissions("carl", true, "admin.services.manage").browser();
        assertThat(ok.patch("/api/admin/services/vault", update("Vault", "#112233")).ok()).isTrue();
    }

    @Test
    void theOverviewListsTheServicesTab() throws Exception {
        Session admin = loggedInAdmin("joshua");
        assertThat(admin.browser().get("/api/admin/overview").body()).contains("\"services\"");
    }

    @Test
    void theLockFlagIsStoredAndShownTogetherWithTheReportedColor() throws Exception {
        vault();
        jdbc.sql("UPDATE services SET reported_accent_color = '#22aa55' WHERE key = 'vault'").update();
        Browser admin = loggedInAdmin("joshua").browser();
        JsonNode before = json.readTree(admin.get("/api/admin/services").body()).get(0);
        assertThat(before.get("reportedAccentColor").asString()).isEqualTo("#22aa55");
        assertThat(before.get("accentLocked").asBoolean()).isFalse();
        Map<String, Object> locked = update("Vaultwarden", "#2f81f7");
        locked.put("accentLocked", true);
        assertThat(admin.patch("/api/admin/services/vault", locked).ok()).isTrue();
        JsonNode after = json.readTree(admin.get("/api/admin/services").body()).get(0);
        assertThat(after.get("accentLocked").asBoolean()).isTrue();
        assertThat(after.get("reportedAccentColor").asString()).isEqualTo("#22aa55"); // die gemeldete Farbe bleibt erhalten
    }

    @Test
    void openModeAndLogoutAddressAreSetAndValidated() throws Exception {
        vault();
        Browser admin = loggedInAdmin("joshua").browser();
        Map<String, Object> m = update("Vaultwarden", "");
        m.put("openMode", "embedded");
        m.put("backchannelLogoutUri", "https://vault.example/auth/backchannel-logout");
        assertThat(admin.patch("/api/admin/services/vault", m).ok()).isTrue();
        JsonNode v = json.readTree(admin.get("/api/admin/services").body()).get(0);
        assertThat(v.get("openMode").asString()).isEqualTo("embedded");
        assertThat(v.get("backchannelLogoutUri").asString()).isEqualTo("https://vault.example/auth/backchannel-logout");

        // ohne Angabe bleibt der Öffnungsmodus, die Logout-Adresse ist wie die anderen Adressen "leer = nicht gesetzt"
        assertThat(admin.patch("/api/admin/services/vault", update("Vaultwarden", "")).ok()).isTrue();
        v = json.readTree(admin.get("/api/admin/services").body()).get(0);
        assertThat(v.get("openMode").asString()).isEqualTo("embedded");
        assertThat(v.get("backchannelLogoutUri").isNull()).isTrue();

        Map<String, Object> bad = update("Vaultwarden", "");
        bad.put("openMode", "popup");
        assertThat(admin.patch("/api/admin/services/vault", bad).body()).contains("service_open_mode_invalid");
        bad = update("Vaultwarden", "");
        bad.put("backchannelLogoutUri", "ftp://vault.example/x");
        assertThat(admin.patch("/api/admin/services/vault", bad).body()).contains("service_logout_url_invalid");
        bad = update("Vaultwarden", "");
        bad.put("openMode", "embedded");
        bad.put("url", "");
        assertThat(admin.patch("/api/admin/services/vault", bad).body()).contains("service_embedded_needs_url");
    }
}
