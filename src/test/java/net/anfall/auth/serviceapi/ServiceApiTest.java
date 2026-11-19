package net.anfall.auth.serviceapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.service.ServiceHealthService;
import net.anfall.auth.service.ServiceRegistrationService;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Die Dienst-Schnittstelle (/api/service/*): Token per client_credentials, Rechte eines Users, Meldungen des Dienstes. */
class ServiceApiTest extends IntegrationTestBase {

    @Autowired ServiceRegistrationService registration;
    @Autowired PermissionService permissions;
    @Autowired ServiceHealthService health;

    private OidcTestClient media;
    private OidcTestClient vault;
    private String mediaToken;
    private UUID anna;

    @BeforeEach
    void setUpServices() throws Exception {
        health.clearAll();
        media = new OidcTestClient(mvc, json, "media", registration.register("media", "Medien", List.of(OidcTestClient.REDIRECT_URI)).clientSecret());
        vault = new OidcTestClient(mvc, json, "vault", registration.register("vault", "Vaultwarden", List.of(OidcTestClient.REDIRECT_URI)).clientSecret());
        jdbc.sql("""
                INSERT INTO permissions (key, type, direction, allow_unlimited, builtin) VALUES
                  ('media.upload', 'boolean', NULL, false, false),
                  ('media.share', 'boolean', NULL, false, false),
                  ('media.quota_gb', 'number', 'higher', true, false),
                  ('media.delay_s', 'number', 'lower', false, false),
                  ('vault.export', 'boolean', NULL, false, false)""").update();
        anna = createUser("anna", "anna@example.com", PASSWORD, true);
        permissions.grantToUser(anna, "service.media");
        mediaToken = media.serviceToken().accessToken();
    }

    private MockHttpServletResponse call(String path, String token) throws Exception {
        var req = get(path);
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    private JsonNode permissionsOf(UUID sub, String query) throws Exception {
        MockHttpServletResponse r = call("/api/service/users/" + sub + "/permissions" + query, mediaToken);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    private MockHttpServletResponse report(String token, String body) throws Exception {
        return mvc.perform(put("/api/service/self").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    // ---- Token ----

    @Test
    void aServiceGetsATokenWithClientCredentialsAndNothingElse() throws Exception {
        var tokens = media.serviceToken();
        assertThat(tokens.status()).isEqualTo(200);
        assertThat(tokens.json().has("refresh_token")).isFalse();
        assertThat(tokens.json().has("id_token")).isFalse();
        var claims = media.verify(tokens.accessToken());          // Signatur gegen das JWKS geprüft
        assertThat(claims.getSubject()).isEqualTo("media");
        assertThat(claims.getStringClaim("token_use")).isEqualTo("service");
        assertThat(claims.getStringListClaim("scope")).containsExactly("service");
        assertThat(claims.getExpirationTime()).isAfter(new java.util.Date());
    }

    @Test
    void theTokenEndpointRefusesAWrongSecretAndUnknownScopes() throws Exception {
        assertThat(media.tokenWithSecret(Map.of("grant_type", "client_credentials", "scope", "service"), "falsch").status()).isEqualTo(401);
        assertThat(media.token(Map.of("grant_type", "client_credentials", "scope", "admin")).status()).isEqualTo(400);
    }

    // ---- Zugriff auf die Schnittstelle ----

    @Test
    void withoutOrWithAnInvalidTokenTheAnswerIs401() throws Exception {
        assertThat(call("/api/service/self", null).getStatus()).isEqualTo(401);
        assertThat(call("/api/service/self", "kein.gueltiges.token").getStatus()).isEqualTo(401);
        assertThat(call("/api/service/users/" + anna + "/permissions", null).getStatus()).isEqualTo(401);
    }

    @Test
    void aUserTokenCanNeverCallTheServiceApi() throws Exception {
        // Ein User holt sich ein Token bei Medien, auch mit dem Scope "service"
        permissions.grantToUser(anna, "service.media");
        Browser b = browser();
        assertThat(login(b, "anna", PASSWORD).ok()).isTrue();
        for (String scope : List.of("openid email", "openid service")) {
            OidcTestClient.Pkce pkce = OidcTestClient.pkce();
            String code = OidcTestClient.queryOf(b.get(media.authorizeUri(pkce, "s", scope)).location()).get("code");
            String userToken = media.exchangeCode(code, pkce.verifier()).accessToken();
            MockHttpServletResponse r = call("/api/service/users/" + anna + "/permissions", userToken);
            assertThat(r.getStatus()).as(scope).isEqualTo(403);
            assertThat(call("/api/service/self", userToken).getStatus()).as(scope).isEqualTo(403);
        }
    }

    // ---- Rechte eines Users ----

    @Test
    void onlyRightsOfTheOwnNamespaceAreReturned() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        permissions.grantToUser(anna, "vault.export");     // anderer Dienst
        permissions.grantToUser(anna, "links.create");     // allgemeines Recht
        JsonNode r = permissionsOf(anna, "");
        assertThat(r.get("sub").asString()).isEqualTo(anna.toString());
        assertThat(r.get("access").asBoolean()).isTrue();
        assertThat(r.get("permissions")).hasSize(1);
        assertThat(r.get("permissions").get(0).get("key").asString()).isEqualTo("media.upload");
        assertThat(r.get("permissions").get(0).get("type").asString()).isEqualTo("boolean");
    }

    @Test
    void numberRightsComeWithTheirComputedValue() throws Exception {
        permissions.setUserValue(anna, "media.quota_gb", PermissionValue.ofNumber(10));
        permissions.setUserValue(anna, "media.delay_s", PermissionValue.ofNumber(30));
        // eine Rolle mit größerem Wert: es gilt der, der mehr Rechte gibt
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Power')").param("i", role).update();
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key, number_value) VALUES (:r, 'media.quota_gb', 50), (:r, 'media.delay_s', 5)").param("r", role).update();
        jdbc.sql("INSERT INTO user_roles (user_id, role_id) VALUES (:u, :r)").param("u", anna).param("r", role).update();
        JsonNode list = permissionsOf(anna, "").get("permissions");
        Map<String, Long> values = new java.util.HashMap<>();
        for (JsonNode e : list) {
            values.put(e.get("key").asString(), e.get("value").asLong());
        }
        assertThat(values).containsEntry("media.quota_gb", 50L).containsEntry("media.delay_s", 5L);
    }

    @Test
    void unlimitedIsReportedAsUnlimitedNotAsANumber() throws Exception {
        permissions.setUserValue(anna, "media.quota_gb", PermissionValue.UNLIMITED);
        JsonNode e = permissionsOf(anna, "").get("permissions").get(0);
        assertThat(e.get("unlimited").asBoolean()).isTrue();
        assertThat(e.has("value")).isFalse();
    }

    @Test
    void notSetRightsAreLeftOutAndAZeroIsAValue() throws Exception {
        assertThat(permissionsOf(anna, "").get("permissions")).isEmpty();
        permissions.setUserValue(anna, "media.delay_s", PermissionValue.ofNumber(0));
        JsonNode e = permissionsOf(anna, "").get("permissions").get(0);
        assertThat(e.get("key").asString()).isEqualTo("media.delay_s");
        assertThat(e.get("value").asLong()).isZero();
    }

    @Test
    void thePrefixNarrowsTheAnswerButNeverLeavesTheNamespace() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        permissions.grantToUser(anna, "media.share");
        permissions.setUserValue(anna, "media.quota_gb", PermissionValue.ofNumber(5));
        assertThat(permissionsOf(anna, "?prefix=media.q").get("permissions")).hasSize(1);
        assertThat(permissionsOf(anna, "?prefix=media.").get("permissions")).hasSize(3);
        assertThat(permissionsOf(anna, "?prefix=").get("permissions")).hasSize(3);
        for (String bad : List.of("vault.", "media", "links.", "admin.")) {
            MockHttpServletResponse r = call("/api/service/users/" + anna + "/permissions?prefix=" + bad, mediaToken);
            assertThat(r.getStatus()).as(bad).isEqualTo(400);
            assertThat(r.getContentAsString()).contains("prefix_not_allowed");
        }
    }

    @Test
    void anotherServiceSeesOnlyItsOwnRights() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        permissions.grantToUser(anna, "service.vault");
        permissions.grantToUser(anna, "vault.export");
        String vaultToken = vault.serviceToken().accessToken();
        JsonNode r = json.readTree(call("/api/service/users/" + anna + "/permissions", vaultToken).getContentAsString());
        assertThat(r.get("permissions")).hasSize(1);
        assertThat(r.get("permissions").get(0).get("key").asString()).isEqualTo("vault.export");
        assertThat(call("/api/service/users/" + anna + "/permissions?prefix=media.", vaultToken).getStatus()).isEqualTo(400);
    }

    @Test
    void withoutAccessToTheServiceTheAnswerHasNoRights() throws Exception {
        UUID bob = createUser("bob", "bob@example.com", PASSWORD, true);
        permissions.grantToUser(bob, "media.upload");        // Recht da, aber kein service.media
        JsonNode r = permissionsOf(bob, "");
        assertThat(r.get("access").asBoolean()).isFalse();
        assertThat(r.get("permissions")).isEmpty();
    }

    @Test
    void aBannedUserHasNoAccess() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        jdbc.sql("UPDATE users SET banned = true WHERE id = :i").param("i", anna).update();
        JsonNode r = permissionsOf(anna, "");
        assertThat(r.get("access").asBoolean()).isFalse();
        assertThat(r.get("permissions")).isEmpty();
    }

    @Test
    void revokingARightShowsImmediatelyNotAfterTheTokenExpires() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        assertThat(permissionsOf(anna, "").get("permissions")).hasSize(1);
        permissions.revokeFromUser(anna, "media.upload");
        assertThat(permissionsOf(anna, "").get("permissions")).isEmpty();
    }

    @Test
    void unknownAndMalformedSubjects() throws Exception {
        assertThat(call("/api/service/users/" + UUID.randomUUID() + "/permissions", mediaToken).getStatus()).isEqualTo(404);
        MockHttpServletResponse bad = call("/api/service/users/nicht-eine-uuid/permissions", mediaToken);
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(bad.getContentAsString()).contains("sub_invalid");
    }

    // ---- Eigene Daten und Meldungen ----

    @Test
    void aServiceReadsItsOwnData() throws Exception {
        jdbc.sql("UPDATE services SET description = 'Eigener Dienst', accent_color = '#112233' WHERE key = 'media'").update();
        JsonNode s = json.readTree(call("/api/service/self", mediaToken).getContentAsString());
        assertThat(s.get("key").asString()).isEqualTo("media");
        assertThat(s.get("displayName").asString()).isEqualTo("Medien");
        assertThat(s.get("accentColor").asString()).isEqualTo("#112233");
        assertThat(s.get("effectiveAccentColor").asString()).isEqualTo("#112233");
        assertThat(s.get("status").asString()).isEqualTo("unknown");
        assertThat(s.has("clientSecret")).isFalse();
    }

    @Test
    void aReportedColorOverridesTheHubColorUnlessLocked() throws Exception {
        jdbc.sql("UPDATE services SET accent_color = '#112233' WHERE key = 'media'").update();
        assertThat(report(mediaToken, "{\"accentColor\":\"#22AA55\"}").getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT reported_accent_color FROM services WHERE key = 'media'").query(String.class).single()).isEqualTo("#22aa55");
        assertThat(json.readTree(call("/api/service/self", mediaToken).getContentAsString()).get("effectiveAccentColor").asString()).isEqualTo("#22aa55");
        // festgehalten: die Hub-Farbe bleibt
        jdbc.sql("UPDATE services SET accent_locked = true WHERE key = 'media'").update();
        assertThat(json.readTree(call("/api/service/self", mediaToken).getContentAsString()).get("effectiveAccentColor").asString()).isEqualTo("#112233");
        // ein leerer Text löscht die gemeldete Farbe
        assertThat(report(mediaToken, "{\"accentColor\":\"\"}").getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT reported_accent_color FROM services WHERE key = 'media'").query(String.class).optional()).isEmpty();
    }

    @Test
    void invalidReportsChangeNothing() throws Exception {
        for (String bad : List.of("{\"accentColor\":\"red\"}", "{\"accentColor\":\"#fff\"}", "{\"status\":{\"state\":\"maybe\"}}",
                "{\"status\":{\"state\":\"up\",\"ttlSeconds\":5}}", "{\"status\":{\"state\":\"up\",\"ttlSeconds\":100000}}")) {
            assertThat(report(mediaToken, bad).getStatus()).as(bad).isEqualTo(400);
        }
        // Farbe gültig, Status ungültig: auch die Farbe wird nicht übernommen
        assertThat(report(mediaToken, "{\"accentColor\":\"#22aa55\",\"status\":{\"state\":\"x\"}}").getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT reported_accent_color FROM services WHERE key = 'media'").query(String.class).optional()).isEmpty();
        assertThat(health.status("media").state()).isEqualTo("unknown");
    }

    @Test
    void aReportedStatusHasPriorityForItsLifetimeAndOneServiceCannotReportForAnother() throws Exception {
        assertThat(report(mediaToken, "{\"status\":{\"state\":\"up\",\"ttlSeconds\":60}}").getStatus()).isEqualTo(200);
        assertThat(health.status("media").state()).isEqualTo("up");
        assertThat(health.status("vault").state()).isEqualTo("unknown");        // der Token gehört nur zu "media"
        assertThat(report(mediaToken, "{\"status\":{\"state\":\"down\"}}").getStatus()).isEqualTo(200);
        assertThat(health.status("media").state()).isEqualTo("down");
    }

    @Test
    void anOmittedFieldStaysUnchanged() throws Exception {
        report(mediaToken, "{\"accentColor\":\"#22aa55\"}");
        report(mediaToken, "{\"status\":{\"state\":\"up\"}}");
        assertThat(jdbc.sql("SELECT reported_accent_color FROM services WHERE key = 'media'").query(String.class).single()).isEqualTo("#22aa55");
    }

    @Test
    void reportsAreWrittenToTheAuditLog() throws Exception {
        report(mediaToken, "{\"accentColor\":\"#22aa55\"}");
        assertThat(jdbc.sql("SELECT details::text FROM audit_log WHERE action = 'service.reported'").query(String.class).single()).contains("media");
    }

    @Test
    void theServiceApiIsRateLimitedPerService() throws Exception {
        // Zähler des Dienstes auf das Limit setzen (600 pro Minute)
        jdbc.sql("INSERT INTO throttle (bucket, key_hash, count, window_start) VALUES ('serviceapi', :k, 600, now())")
                .param("k", Tokens.sha256Hex("media")).update();
        assertThat(call("/api/service/self", mediaToken).getStatus()).isEqualTo(429);
        // ein anderer Dienst ist nicht betroffen
        assertThat(call("/api/service/self", vault.serviceToken().accessToken()).getStatus()).isEqualTo(200);
    }

    @Test
    void thePermissionsAreVisibleInTheHubOnlyViaTheirOwnApiNotInTheUserToken() throws Exception {
        permissions.grantToUser(anna, "media.upload");
        Browser b = browser();
        login(b, "anna", PASSWORD);
        OidcTestClient.Pkce pkce = OidcTestClient.pkce();
        String code = OidcTestClient.queryOf(b.get(media.authorizeUri(pkce, "s", "openid profile email offline_access")).location()).get("code");
        var tokens = media.exchangeCode(code, pkce.verifier());
        assertThat(media.verify(tokens.accessToken()).getClaims().keySet()).doesNotContain("permissions", "media.upload");
        assertThat(tokens.accessToken().length()).isLessThan(1200);   // klein, auch bei vielen Rechten
    }

    // ---- Nutzerabfrage: Der Dienst kennt nur die UUID und holt Namen und Adresse hier ab ----

    @Test
    void aServiceListsTheUsersWithAccessAndLooksUpOthersBySub() throws Exception {
        UUID ben = createUser("ben", "ben@example.com", PASSWORD, true, "de");      // ohne Zugang
        UUID cara = createUser("cara", "cara@example.com", PASSWORD, true);
        permissions.grantToUser(cara, "service.media");

        JsonNode all = json.readTree(call("/api/service/users", mediaToken).getContentAsString()).get("users");
        assertThat(all).hasSize(2);                                         // anna und cara, nicht ben
        assertThat(all.get(0).get("username").asString()).isEqualTo("anna");
        assertThat(all.get(0).get("email").asString()).isEqualTo("anna@example.com");
        assertThat(all.get(0).get("emailVerified").asBoolean()).isTrue();
        assertThat(all.get(0).get("access").asBoolean()).isTrue();

        // Abfrage per sub liefert auch Nutzer ohne Zugang (der Dienst kennt die UUID aus seinen Daten), mit access=false
        JsonNode some = json.readTree(call("/api/service/users?sub=" + ben + "&sub=" + anna + "&sub=" + UUID.randomUUID(), mediaToken)
                .getContentAsString()).get("users");
        assertThat(some).hasSize(2);                                        // unbekannte UUID fehlt einfach
        assertThat(some.get(0).get("access").asBoolean()).isFalse();
        assertThat(some.get(0).get("locale").asString()).isEqualTo("de");
        assertThat(some.get(1).get("access").asBoolean()).isTrue();
    }

    @Test
    void aBannedUserIsStillResolvableButHasNoAccess() throws Exception {
        jdbc.sql("UPDATE users SET banned = true WHERE id = :id").param("id", anna).update();
        JsonNode u = json.readTree(call("/api/service/users?sub=" + anna, mediaToken).getContentAsString()).get("users").get(0);
        assertThat(u.get("banned").asBoolean()).isTrue();
        assertThat(u.get("access").asBoolean()).isFalse();
        assertThat(json.readTree(call("/api/service/users", mediaToken).getContentAsString()).get("users")).isEmpty();
    }

    @Test
    void theUserLookupIsLimitedAndNeedsAServiceToken() throws Exception {
        StringBuilder q = new StringBuilder("?");
        for (int i = 0; i < 101; i++) {
            q.append("sub=").append(UUID.randomUUID()).append('&');
        }
        assertThat(call("/api/service/users" + q, mediaToken).getStatus()).isEqualTo(400);
        assertThat(call("/api/service/users?sub=not-a-uuid", mediaToken).getStatus()).isEqualTo(400);
        assertThat(call("/api/service/users", null).getStatus()).isEqualTo(401);
    }
}
