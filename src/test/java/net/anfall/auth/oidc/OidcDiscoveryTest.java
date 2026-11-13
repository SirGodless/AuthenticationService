package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.nimbusds.jose.jwk.JWKSet;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Discovery und JWKS: Was Vaultwarden und Audiobookshelf beim Einrichten als Erstes abrufen. */
class OidcDiscoveryTest extends IntegrationTestBase {

    private JsonNode discovery() throws Exception {
        String body = mvc.perform(get("/.well-known/openid-configuration")).andReturn().getResponse()
                .getContentAsString();
        return json.readTree(body);
    }

    @Test
    void discoveryDocumentShowsTheFixedIssuerAndTheEndpoints() throws Exception {
        JsonNode d = discovery();
        assertThat(d.get("issuer").asString()).isEqualTo("https://auth.example.test");
        assertThat(d.get("authorization_endpoint").asString()).endsWith("/oauth2/authorize");
        assertThat(d.get("token_endpoint").asString()).endsWith("/oauth2/token");
        assertThat(d.get("jwks_uri").asString()).endsWith("/oauth2/jwks");
        assertThat(d.get("userinfo_endpoint").asString()).endsWith("/userinfo");
    }

    @Test
    void discoveryAdvertisesCodeFlowWithPkceAndRs256() throws Exception {
        JsonNode d = discovery();
        assertThat(d.get("response_types_supported").toString()).contains("code");
        assertThat(d.get("grant_types_supported").toString()).contains("authorization_code", "refresh_token");
        assertThat(d.get("code_challenge_methods_supported").toString()).contains("S256");
        assertThat(d.get("id_token_signing_alg_values_supported").toString()).contains("RS256");
        assertThat(d.get("subject_types_supported").toString()).contains("public");
        assertThat(d.get("scopes_supported").toString()).contains("openid");
    }

    @Test
    void jwksHasOneRs256SigningKeyAndNeverShowsPrivateParts() throws Exception {
        String body = mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
        JWKSet set = JWKSet.parse(body);
        assertThat(set.getKeys()).hasSize(1);
        var key = set.getKeys().get(0);
        assertThat(key.getKeyType().getValue()).isEqualTo("RSA");
        assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(key.getKeyUse().getValue()).isEqualTo("sig");
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.isPrivate()).isFalse();
        assertThat(body).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
    }

    @Test
    void unauthenticatedTokenAndUserInfoCallsAreRejectedWith401() throws Exception {
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/oauth2/token")
                .param("grant_type", "authorization_code")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/userinfo")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
