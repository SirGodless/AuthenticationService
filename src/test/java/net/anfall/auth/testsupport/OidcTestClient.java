package net.anfall.auth.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Ein Test-Dienst, der den OIDC-Ablauf durchläuft: PKCE, Code tauschen, Tokens prüfen, UserInfo. */
public class OidcTestClient {

    public static final String REDIRECT_URI = "https://client.example/callback";

    public record Pkce(String verifier, String challenge) {
    }

    public record TokenResponse(int status, JsonNode json) {
        public String accessToken() {
            return json.path("access_token").asString();
        }

        public String idToken() {
            return json.path("id_token").asString();
        }

        public String refreshToken() {
            return json.path("refresh_token").asString();
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;
    private final String clientId;
    private final String clientSecret;

    public OidcTestClient(MockMvc mvc, JsonMapper json, String clientId, String clientSecret) {
        this.mvc = mvc;
        this.json = json;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public static Pkce pkce() throws Exception {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(hash));
    }

    /** Die Adresse, zu der ein Dienst den Browser schickt. */
    public URI authorizeUri(Pkce pkce, String state, String scope) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", clientId)
                .queryParam("redirect_uri", REDIRECT_URI).queryParam("scope", scope).queryParam("state", state);
        if (pkce != null) {
            b.queryParam("code_challenge", pkce.challenge()).queryParam("code_challenge_method", "S256");
        }
        return b.build().encode().toUri();
    }

    /** Die Parameter einer Weiterleitung (Query), z. B. code und state. */
    public static Map<String, String> queryOf(String location) {
        Map<String, String> out = new LinkedHashMap<>();
        UriComponentsBuilder.fromUriString(location).build().getQueryParams()
                .forEach((k, v) -> out.put(k, java.net.URLDecoder.decode(v.get(0), StandardCharsets.UTF_8)));
        return out;
    }

    public TokenResponse exchangeCode(String code, String verifier) throws Exception {
        return token(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                "code_verifier", verifier));
    }

    public TokenResponse refresh(String refreshToken) throws Exception {
        return token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    }

    public TokenResponse token(Map<String, String> params) throws Exception {
        var request = post("/oauth2/token").header(HttpHeaders.AUTHORIZATION, basic(clientId, clientSecret));
        for (var e : params.entrySet()) {
            request.param(e.getKey(), e.getValue());
        }
        MockHttpServletResponse res = mvc.perform(request).andReturn().getResponse();
        String body = res.getContentAsString();
        return new TokenResponse(res.getStatus(), body.isBlank() ? json.createObjectNode() : json.readTree(body));
    }

    public TokenResponse tokenWithSecret(Map<String, String> params, String secret) throws Exception {
        return new OidcTestClient(mvc, json, clientId, secret).token(params);
    }

    public MockHttpServletResponse userInfo(String accessToken) throws Exception {
        return mvc.perform(get("/userinfo").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn().getResponse();
    }

    /** Öffentliche Schlüssel des Providers. */
    public JWKSet jwks() throws Exception {
        return JWKSet.parse(mvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString());
    }

    /** Prüft die Signatur gegen das JWKS des Providers und gibt die Claims zurück. */
    public JWTClaimsSet verify(String jwt) throws Exception {
        SignedJWT signed = SignedJWT.parse(jwt);
        RSAKey key = (RSAKey) jwks().getKeyByKeyId(signed.getHeader().getKeyID());
        if (key == null || !signed.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
            throw new IllegalStateException("Signatur ungültig oder Schlüssel unbekannt");
        }
        return signed.getJWTClaimsSet();
    }

    public static String keyIdOf(String jwt) throws Exception {
        return SignedJWT.parse(jwt).getHeader().getKeyID();
    }

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }
}
