package net.anfall.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Dienste im Dashboard anlegen und verwalten: Anlegen, Rücksprung-Adressen, Client-Geheimnis. */
class ServiceDashboardTest extends AdminTestBase {

    private static Map<String, Object> create(String key, List<String> uris) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("displayName", "Medien");
        m.put("redirectUris", uris);
        m.put("description", "Eigener Dienst");
        m.put("url", "https://media.example");
        m.put("healthUrl", "https://media.example/health");
        m.put("accentColor", "#E5A00D");
        return m;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void anAdminCreatesAServiceWithClientRightAndSecretShownOnce() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        Browser.Result r = admin.post("/api/admin/services", create("media", List.of("https://media.example/callback")));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = json.readTree(r.body());
        String secret = body.get("clientSecret").asString();
        assertThat(secret).hasSize(43);
        assertThat(body.get("service").get("clientId").asString()).isEqualTo("media");
        assertThat(body.get("service").get("accentColor").asString()).isEqualTo("#e5a00d");
        assertThat(body.get("service").get("redirectUris").get(0).asString()).isEqualTo("https://media.example/callback");

        // Recht service.media entstanden, Client vorhanden, Geheimnis nur gehasht gespeichert
        assertThat(jdbc.sql("SELECT count(*) FROM permissions WHERE key = 'service.media'").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT client_secret FROM oauth2_registered_client WHERE client_id = 'media'").query(String.class).single())
                .isNotEqualTo(secret).doesNotContain(secret);
        // Das Geheimnis taucht in keiner späteren Antwort und nicht im Protokoll auf
        assertThat(admin.get("/api/admin/services").body()).doesNotContain(secret).doesNotContain("clientSecret");
        assertThat(jdbc.sql("SELECT string_agg(details::text, ' ') FROM audit_log").query(String.class).single()).doesNotContain(secret);
        // und das Geheimnis funktioniert am Token-Endpunkt
        assertThat(new OidcTestClient(mvc, json, "media", secret).serviceToken().status()).isEqualTo(200);
    }

    @Test
    void onlyKeyNameAndRedirectUrisAreRequired() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        Map<String, Object> minimal = new HashMap<>();
        minimal.put("key", "media");
        minimal.put("displayName", "Medien");
        minimal.put("redirectUris", List.of("https://media.example/callback"));
        assertThat(admin.post("/api/admin/services", minimal).ok()).isTrue();
        JsonNode s = json.readTree(admin.get("/api/admin/services").body()).get(0);
        assertThat(s.get("url").isNull()).isTrue();
        assertThat(s.get("accentColor").isNull()).isTrue();
    }

    @Test
    void invalidInputCreatesNothing() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.post("/api/admin/services", create("Bad Key", List.of("https://a.example/cb"))).body()).contains("service_key_invalid");
        assertThat(admin.post("/api/admin/services", create("x", List.of("https://a.example/cb"))).status()).isEqualTo(400);
        assertThat(admin.post("/api/admin/services", create("media", List.of())).status()).isEqualTo(400);
        Map<String, Object> badColor = create("media", List.of("https://a.example/cb"));
        badColor.put("accentColor", "red");
        assertThat(admin.post("/api/admin/services", badColor).body()).contains("service_color_invalid");
        Map<String, Object> badUrl = create("media", List.of("https://a.example/cb"));
        badUrl.put("url", "javascript:alert(1)");
        assertThat(admin.post("/api/admin/services", badUrl).body()).contains("service_url_invalid");
        Map<String, Object> noName = create("media", List.of("https://a.example/cb"));
        noName.put("displayName", " ");
        assertThat(admin.post("/api/admin/services", noName).body()).contains("service_name_invalid");
        // nichts davon hat etwas angelegt
        assertThat(count("services")).isZero();
        assertThat(count("oauth2_registered_client")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM permissions WHERE key = 'service.media'").query(Long.class).single()).isZero();
    }

    @Test
    void redirectUriRulesAreStrict() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        for (String bad : List.of("javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "http://evil.example/cb",
                "https://a.example/*", "https://a.example/cb#frag", "not a uri", "https://", "ftp://a.example/x", "")) {
            Browser.Result r = admin.post("/api/admin/services", create("media", List.of(bad)));
            assertThat(r.status()).as("sollte abgelehnt werden: " + bad).isEqualTo(400);
            assertThat(r.body()).contains("redirect_uri_invalid");
        }
        assertThat(count("services")).isZero();
        // erlaubt: https, http nur für localhost, eigene App-Schemas
        assertThat(admin.post("/api/admin/services", create("media", List.of("https://a.example/cb", "http://localhost:3000/cb",
                "http://127.0.0.1/cb", "audiobookshelf://oauth"))).ok()).isTrue();
        // höchstens 10
        Browser.Result tooMany = admin.put("/api/admin/services/media/redirect-uris",
                Map.of("redirectUris", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "https://a.example/" + i).toList()));
        assertThat(tooMany.status()).isEqualTo(400);
    }

    @Test
    void keyAndClientIdMustBeUnique() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        assertThat(admin.post("/api/admin/services", create("media", List.of("https://a.example/cb"))).ok()).isTrue();
        assertThat(admin.post("/api/admin/services", create("media", List.of("https://b.example/cb"))).status()).isEqualTo(409);
    }

    @Test
    void redirectUrisCanBeReplacedAndAreCheckedAtAuthorize() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        admin.post("/api/admin/services", create("media", List.of("https://a.example/cb")));
        assertThat(admin.put("/api/admin/services/media/redirect-uris",
                Map.of("redirectUris", List.of("https://b.example/cb", "https://b.example/cb", "https://c.example/cb"))).ok()).isTrue();
        // doppelte fallen weg
        assertThat(json.readTree(admin.get("/api/admin/services").body()).get(0).get("redirectUris")).hasSize(2);
        // ungültige ändern nichts
        assertThat(admin.put("/api/admin/services/media/redirect-uris", Map.of("redirectUris", List.of("javascript:x"))).status()).isEqualTo(400);
        assertThat(json.readTree(admin.get("/api/admin/services").body()).get(0).get("redirectUris")).hasSize(2);
        assertThat(admin.put("/api/admin/services/gibtsnicht/redirect-uris", Map.of("redirectUris", List.of("https://b.example/cb"))).status()).isEqualTo(404);
        assertThat(admin.put("/api/admin/services/media/redirect-uris", Map.of("redirectUris", List.of())).status()).isEqualTo(400);
    }

    @Test
    void aNewSecretReplacesTheOldOneImmediately() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        String old = json.readTree(admin.post("/api/admin/services", create("media", List.of("https://a.example/cb"))).body())
                .get("clientSecret").asString();
        String fresh = json.readTree(admin.post("/api/admin/services/media/secret", Map.of()).body()).get("clientSecret").asString();
        assertThat(fresh).hasSize(43).isNotEqualTo(old);
        assertThat(new OidcTestClient(mvc, json, "media", old).serviceToken().status()).isEqualTo(401);
        assertThat(new OidcTestClient(mvc, json, "media", fresh).serviceToken().status()).isEqualTo(200);
        assertThat(admin.post("/api/admin/services/unknown/secret", Map.of()).status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT string_agg(details::text, ' ') FROM audit_log").query(String.class).single()).doesNotContain(fresh).doesNotContain(old);
    }

    @Test
    void creatingNeedsTheServiceRightAndTwoFactor() throws Exception {
        assertThat(loggedInWithPermissions("anna", true, "admin.users.view").browser()
                .post("/api/admin/services", create("media", List.of("https://a.example/cb"))).status()).isEqualTo(403);
        assertThat(loggedInWithPermissions("bob", false, "admin.services.manage").browser()
                .post("/api/admin/services", create("media", List.of("https://a.example/cb"))).body()).contains("two_factor_required");
        assertThat(count("services")).isZero();
    }

    @Test
    void theRightsListShowsWhichServiceARightBelongsTo() throws Exception {
        Browser admin = loggedInAdmin("joshua").browser();
        admin.post("/api/admin/services", create("media", List.of("https://a.example/cb")));
        admin.post("/api/admin/permissions", Map.of("key", "media.upload", "type", "boolean", "direction", "", "allowUnlimited", false,
                "linkGrantable", false, "description", "x"));
        admin.post("/api/admin/permissions", Map.of("key", "other.thing", "type", "boolean", "direction", "", "allowUnlimited", false,
                "linkGrantable", false, "description", "x"));
        JsonNode list = json.readTree(admin.get("/api/admin/permissions").body());
        String owners = list.valueStream().filter(n -> n.get("definition").get("key").asString().matches("media\\.upload|other\\.thing"))
                .map(n -> n.get("definition").get("key").asString() + "=" + (n.get("ownerService").isNull() ? "-" : n.get("ownerService").asString()))
                .sorted().reduce("", (a, b) -> a + b + ";");
        assertThat(owners).isEqualTo("media.upload=media;other.thing=-;");
    }
}
