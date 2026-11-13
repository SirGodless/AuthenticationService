package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/** Der Authorization-Code-Flow mit PKCE von der Anmeldung bis zu den Tokens, wie ihn ein Dienst durchläuft. */
class OidcAuthorizationCodeFlowTest extends OidcTestBase {

    @BeforeEach
    void setUp() {
        setUpVaultAndJoshua();
    }

    @Test
    void notSignedInUsersAreSentToTheLoginPageAndBackToTheSameAuthorizeAddress() throws Exception {
        OidcTestClient.Pkce pkce = OidcTestClient.pkce();
        var uri = vault.authorizeUri(pkce, "s1", SCOPES);
        Browser.Result r = browser().get(uri);

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.location()).startsWith("/login?continue=");
        String back = URLDecoder.decode(r.location().substring("/login?continue=".length()), StandardCharsets.UTF_8);
        assertThat(back).startsWith("/oauth2/authorize?").contains("client_id=vault").contains("state=s1");
    }

    @Test
    void aPendingLoginWithMissingSecondFactorCountsAsNotSignedIn() throws Exception {
        enableTotp(joshua);
        Browser b = browser();
        assertThat(login(b, "joshua", PASSWORD).body()).contains("totp_required");
        assertThat(b.get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES)).location())
                .startsWith("/login?continue=");
    }

    @Test
    void fullFlowIssuesCodeThenSignedTokensWithTheClaimsVaultwardenNeeds() throws Exception {
        Browser b = loggedIn("joshua");
        OidcTestClient.TokenResponse tokens = fullLogin(b);

        // ID-Token: iss, sub (= User-UUID), aud, email, email_verified, preferred_username
        JWTClaimsSet id = vault.verify(tokens.idToken());
        assertThat(id.getIssuer()).isEqualTo("https://auth.example.test");
        assertThat(id.getSubject()).isEqualTo(joshua.toString());
        assertThat(id.getAudience()).containsExactly("vault");
        assertThat(id.getStringClaim("email")).isEqualTo("joshua@example.com");
        assertThat(id.getBooleanClaim("email_verified")).isTrue();
        assertThat(id.getStringClaim("preferred_username")).isEqualTo("joshua");
        assertThat(id.getStringClaim("locale")).isEqualTo("de");

        // Access-Token: signiert, 10 Minuten gültig
        JWTClaimsSet access = vault.verify(tokens.accessToken());
        assertThat(access.getSubject()).isEqualTo(joshua.toString());
        long seconds = (access.getExpirationTime().getTime() - access.getIssueTime().getTime()) / 1000;
        assertThat(seconds).isEqualTo(600);
        assertThat(tokens.json().path("expires_in").asInt()).isBetween(595, 600);
        assertThat(tokens.json().path("token_type").asString()).isEqualToIgnoringCase("Bearer");
        assertThat(tokens.refreshToken()).isNotBlank();   // offline_access
    }

    @Test
    void userInfoReturnsTheSameClaimsForAValidAccessToken() throws Exception {
        OidcTestClient.TokenResponse tokens = fullLogin(loggedIn("joshua"));
        MockHttpServletResponse res = vault.userInfo(tokens.accessToken());
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode info = json.readTree(res.getContentAsString());
        assertThat(info.get("sub").asString()).isEqualTo(joshua.toString());
        assertThat(info.get("email").asString()).isEqualTo("joshua@example.com");
        assertThat(info.get("email_verified").asBoolean()).isTrue();
        assertThat(info.get("preferred_username").asString()).isEqualTo("joshua");
        assertThat(vault.userInfo("kein.gueltiges.token").getStatus()).isEqualTo(401);
    }

    @Test
    void onlyRequestedScopesAppearInTheIdToken() throws Exception {
        Browser b = loggedIn("joshua");
        OidcTestClient.Pkce pkce = OidcTestClient.pkce();
        Browser.Result r = b.get(vault.authorizeUri(pkce, "s", "openid"));
        String code = OidcTestClient.queryOf(r.location()).get("code");
        JWTClaimsSet id = vault.verify(vault.exchangeCode(code, pkce.verifier()).idToken());
        assertThat(id.getClaims()).doesNotContainKeys("email", "email_verified", "preferred_username");
    }

    @Test
    void refreshTokensAreIssuedForEveryClientWithTheRefreshGrant_notOnlyWithOfflineAccess() throws Exception {
        // Bewusst großzügig (siehe docs/decisions/oidc-provider.md): Dienste, die offline_access nicht anfordern, laufen trotzdem
        Browser b = loggedIn("joshua");
        OidcTestClient.Pkce pkce = OidcTestClient.pkce();
        String code = OidcTestClient.queryOf(b.get(vault.authorizeUri(pkce, "s", "openid email")).location()).get("code");
        assertThat(vault.exchangeCode(code, pkce.verifier()).refreshToken()).isNotBlank();
    }

    @Test
    void pkceIsRequired_withoutChallengeNoCodeIsIssued() throws Exception {
        Browser b = loggedIn("joshua");
        Browser.Result r = b.get(vault.authorizeUri(null, "s", SCOPES));
        assertThat(r.status()).isEqualTo(302);
        Map<String, String> q = OidcTestClient.queryOf(r.location());
        assertThat(q).containsEntry("error", "invalid_request").doesNotContainKey("code");
    }

    @Test
    void aWrongCodeVerifierIsRejectedAndTheCodeWorksOnlyOnce() throws Exception {
        CodeGrant grant = authorize(loggedIn("joshua"));

        assertThat(vault.exchangeCode(grant.code(), "ein-falscher-verifier-ein-falscher-verifier-1").status())
                .isEqualTo(400);
        OidcTestClient.TokenResponse ok = vault.exchangeCode(grant.code(), grant.pkce().verifier());
        // Der falsche Versuch hat den Code nicht verbraucht oder doch: In beiden Fällen gilt er danach nur einmal
        assertThat(ok.status()).isIn(200, 400);
        assertThat(vault.exchangeCode(grant.code(), grant.pkce().verifier()).status()).isEqualTo(400);
    }

    @Test
    void aWrongRedirectUriNeverRedirects() throws Exception {
        Browser b = loggedIn("joshua");
        var uri = org.springframework.web.util.UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", "vault")
                .queryParam("redirect_uri", "https://boese.example/callback").queryParam("scope", "openid")
                .queryParam("code_challenge", "abc").queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
        Browser.Result r = b.get(uri);
        assertThat(r.location()).isNull();
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void aWrongClientSecretIsRejected() throws Exception {
        CodeGrant grant = authorize(loggedIn("joshua"));
        var res = vault.tokenWithSecret(Map.of("grant_type", "authorization_code", "code", grant.code(),
                "redirect_uri", OidcTestClient.REDIRECT_URI, "code_verifier", grant.pkce().verifier()), "falsch");
        assertThat(res.status()).isEqualTo(401);
    }

    @Test
    void theServerCreatesNoJavaSessionCookie() throws Exception {
        Browser b = loggedIn("joshua");
        Browser.Result r = b.get(vault.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
        assertThat(r.setCookies().stream().filter(c -> c.startsWith("JSESSIONID"))).isEmpty();
    }

    @Test
    void successfulServiceLoginIsAudited() throws Exception {
        fullLogin(loggedIn("joshua"));
        assertThat(jdbc.sql("SELECT subject_id, details::text AS details FROM audit_log WHERE action = 'oidc.login'")
                .query().singleRow()).containsEntry("subject_id", joshua);
    }
}
