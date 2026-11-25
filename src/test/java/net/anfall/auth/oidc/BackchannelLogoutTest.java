package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.anfall.auth.service.ServiceRepository;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Gemeinsames Abmelden (Back-Channel-Logout): Abmelden im Hub schickt dem Dienst ein signiertes Logout-Token und beendet
 * seine Autorisierung. Ein kleiner lokaler Webserver spielt die Logout-Adresse des Dienstes.
 */
class BackchannelLogoutTest extends OidcTestBase {

    @Autowired ServiceRepository services;
    @Autowired TokenRevocationService revocation;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile int answer = 200;

    /** Startet die "Logout-Adresse" des Dienstes vault und trägt sie im Hub ein. */
    private void startLogoutEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logout", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(exchange.getRequestHeaders().getFirst("Content-Type") + "|" + body);
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        server.start();
        var s = services.findByKey("vault").orElseThrow();
        services.updateDisplay("vault", s.displayName(), s.description(), s.url(), s.accentColor(), s.accentLocked(),
                s.healthUrl(), s.openMode(), "http://127.0.0.1:" + server.getAddress().getPort() + "/logout");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void awaitDeliveries(int count) throws Exception {
        for (int i = 0; i < 100 && received.size() < count; i++) {
            Thread.sleep(50);
        }
        assertThat(received).hasSize(count);
    }

    private String tokenOf(String delivery) {
        String body = delivery.substring(delivery.indexOf('|') + 1);
        assertThat(body).startsWith("logout_token=");
        return URLDecoder.decode(body.substring("logout_token=".length()), StandardCharsets.UTF_8);
    }

    @Test
    void discoveryAnnouncesBackchannelLogout() throws Exception {
        setUpVaultAndJoshua();
        var doc = json.readTree(mvc.perform(get("/.well-known/openid-configuration")).andReturn().getResponse().getContentAsString());
        assertThat(doc.path("backchannel_logout_supported").asBoolean()).isTrue();
        assertThat(doc.path("backchannel_logout_session_supported").asBoolean()).isTrue();
    }

    @Test
    void idTokenCarriesTheSessionId() throws Exception {
        setUpVaultAndJoshua();
        var tokens = fullLogin(loggedIn("joshua"));
        assertThat(vault.verify(tokens.idToken()).getStringClaim("sid")).isNotBlank();
    }

    @Test
    void signingOutOfTheHubSendsALogoutTokenAndKillsTheRefreshToken() throws Exception {
        setUpVaultAndJoshua();
        startLogoutEndpoint();
        Browser b = loggedIn("joshua");
        var tokens = fullLogin(b);
        String sid = vault.verify(tokens.idToken()).getStringClaim("sid");

        assertThat(b.post("/api/auth/logout", Map.of()).ok()).isTrue();
        awaitDeliveries(1);

        assertThat(received.get(0)).startsWith("application/x-www-form-urlencoded|");
        String jwt = tokenOf(received.get(0));
        assertThat(SignedJWT.parse(jwt).getHeader().getType().getType()).isEqualTo("logout+jwt");
        JWTClaimsSet c = vault.verify(jwt);
        assertThat(c.getIssuer()).isEqualTo(vault.verify(tokens.idToken()).getIssuer());
        assertThat(c.getSubject()).isEqualTo(joshua.toString());
        assertThat(c.getAudience()).containsExactly("vault");
        assertThat(c.getStringClaim("sid")).isEqualTo(sid);
        assertThat(c.getJWTID()).isNotBlank();
        assertThat(c.getIssueTime()).isNotNull();
        assertThat(c.getExpirationTime()).isAfter(c.getIssueTime());
        assertThat(c.getClaim("nonce")).isNull();
        assertThat(c.getJSONObjectClaim("events")).containsKey("http://schemas.openid.net/event/backchannel-logout");

        // Der Dienst kann sich nicht stillschweigend wieder anmelden: der Refresh-Token ist tot
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'service.backchannel_logout' AND details->>'ok' = 'true'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void onlyServiceSessionsFromTheLoggedOutHubSessionEnd() throws Exception {
        setUpVaultAndJoshua();
        startLogoutEndpoint();
        Browser laptop = loggedIn("joshua");
        Browser phone = loggedIn("joshua");
        var laptopTokens = fullLogin(laptop);
        var phoneTokens = fullLogin(phone);

        assertThat(laptop.post("/api/auth/logout", Map.of()).ok()).isTrue();
        awaitDeliveries(1);

        assertThat(vault.verify(tokenOf(received.get(0))).getStringClaim("sid"))
                .isEqualTo(vault.verify(laptopTokens.idToken()).getStringClaim("sid"))
                .isNotEqualTo(vault.verify(phoneTokens.idToken()).getStringClaim("sid"));
        assertThat(vault.refresh(laptopTokens.refreshToken()).status()).isEqualTo(400);
        assertThat(vault.refresh(phoneTokens.refreshToken()).status()).isEqualTo(200);
    }

    @Test
    void banOrResetEndsAllSessionsAndSendsATokenWithoutSid() throws Exception {
        setUpVaultAndJoshua();
        startLogoutEndpoint();
        var tokens = fullLogin(loggedIn("joshua"));

        assertThat(revocation.revokeAll(joshua)).isEqualTo(1);
        awaitDeliveries(1);

        JWTClaimsSet c = vault.verify(tokenOf(received.get(0)));
        assertThat(c.getSubject()).isEqualTo(joshua.toString());
        assertThat(c.getClaim("sid")).isNull();
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
    }

    @Test
    void aServiceWithoutLogoutAddressIsNotCalledButItsRefreshTokenStillDies() throws Exception {
        setUpVaultAndJoshua();
        Browser b = loggedIn("joshua");
        var tokens = fullLogin(b);
        assertThat(b.post("/api/auth/logout", Map.of()).ok()).isTrue();
        assertThat(vault.refresh(tokens.refreshToken()).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'service.backchannel_logout'").query(Long.class).single()).isZero();
    }

    @Test
    void aFailingServiceDoesNotBlockSigningOutAndTheFailureIsLogged() throws Exception {
        setUpVaultAndJoshua();
        answer = 500;
        startLogoutEndpoint();
        Browser b = loggedIn("joshua");
        fullLogin(b);
        assertThat(b.post("/api/auth/logout", Map.of()).ok()).isTrue();
        awaitDeliveries(1);
        for (int i = 0; i < 100 && jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'service.backchannel_logout'")
                .query(Long.class).single() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(jdbc.sql("SELECT details->>'ok' FROM audit_log WHERE action = 'service.backchannel_logout'")
                .query(String.class).single()).isEqualTo("false");
    }

    @Test
    void theLogoutTokenReachesAServiceThatDropsHttp2UpgradeRequests() throws Exception {
        setUpVaultAndJoshua();
        try (var service = new net.anfall.auth.testsupport.UpgradeAverseServer(200)) {
            var s = services.findByKey("vault").orElseThrow();
            services.updateDisplay("vault", s.displayName(), s.description(), s.url(), s.accentColor(), s.accentLocked(),
                    s.healthUrl(), s.openMode(), "http://127.0.0.1:" + service.port() + "/auth/backchannel-logout");
            Browser b = loggedIn("joshua");
            fullLogin(b);
            assertThat(b.post("/api/auth/logout", Map.of()).ok()).isTrue();
            for (int i = 0; i < 100 && service.requests.isEmpty(); i++) {
                Thread.sleep(50);
            }
            assertThat(service.requests).hasSize(1);
            assertThat(service.requests.get(0)).startsWith("POST /auth/backchannel-logout").contains("logout_token=");
        }
    }
}
