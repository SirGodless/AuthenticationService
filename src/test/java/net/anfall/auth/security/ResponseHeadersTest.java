package net.anfall.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import net.anfall.auth.testsupport.IntegrationTestBase;

/** Sicherheits-Header der API und CORS (CLAUDE.md Abschnitt 10): CORS ist nirgends freigegeben. */
class ResponseHeadersTest extends IntegrationTestBase {

    @Test
    void apiResponsesAreNotCachedNotSniffedAndNotFramed() throws Exception {
        var res = mvc.perform(MockMvcRequestBuilders.get("/api/auth/me")).andReturn().getResponse();
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    void noCrossOriginRequestIsAllowedAnywhere() throws Exception {
        for (String path : new String[]{"/api/auth/me", "/api/hub", "/api/admin/users", "/api/invite/x", "/oauth2/jwks", "/.well-known/openid-configuration"}) {
            // normale Anfrage mit fremdem Origin: keine CORS-Freigabe
            var res = mvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.ORIGIN, "https://evil.example")).andReturn().getResponse();
            assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).as(path).isNull();
            assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).as(path).isNull();
            // Preflight: ebenfalls keine Freigabe
            var pre = mvc.perform(MockMvcRequestBuilders.options(path).header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")).andReturn().getResponse();
            assertThat(pre.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).as("preflight " + path).isNull();
        }
    }

    @Test
    void sessionCookieStaysHostOnlyWithoutADomain() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        var b = browser();
        var r = login(b, "joshua", PASSWORD);
        String cookie = r.setCookies().stream().filter(c -> c.startsWith(SID)).findFirst().orElseThrow();
        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
    }

    @Test
    void thePasswordAndTokensNeverAppearInResponsesOfTheLogin() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        var r = login(browser(), "joshua", PASSWORD);
        assertThat(r.body()).doesNotContain(PASSWORD).doesNotContain("password_hash");
    }
}
