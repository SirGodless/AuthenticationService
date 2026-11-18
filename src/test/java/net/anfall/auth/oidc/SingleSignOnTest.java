package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.Test;

/**
 * Einmal anmelden, überall drin (wie bei Google): Wer eine Anmeldung beim Provider hat, bekommt von jedem Dienst, für den
 * das Recht da ist, sofort einen Code, ohne erneute Eingabe. Die Dienste bleiben direkt aufrufbar, der Hub ist dafür nicht nötig.
 */
class SingleSignOnTest extends OidcTestBase {

    private OidcTestClient audiobookshelf;

    private void setUpSecondService() {
        var registered = registration.register("abs", "Audiobookshelf", List.of(OidcTestClient.REDIRECT_URI));
        audiobookshelf = new OidcTestClient(mvc, json, "abs", registered.clientSecret());
        permissions.grantToUser(joshua, "service.abs");
    }

    private Browser.Result authorize(Browser b, OidcTestClient client) throws Exception {
        return b.get(client.authorizeUri(OidcTestClient.pkce(), "s", SCOPES));
    }

    @Test
    void aServiceOpenedDirectlySendsToLoginAndAnotherServiceThenNeedsNoLogin() throws Exception {
        setUpVaultAndJoshua();
        setUpSecondService();
        Browser b = browser();

        // 1. Der Nutzer ruft Vaultwarden direkt auf, nicht über den Hub: Weiterleitung zur Anmeldung mit Rücksprung
        Browser.Result first = authorize(b, vault);
        assertThat(first.status()).isEqualTo(302);
        assertThat(first.location()).contains("/login?continue=");

        // 2. Anmelden (hier das Passwort, dasselbe wie im Hub)
        assertThat(login(b, "joshua", PASSWORD).ok()).isTrue();

        // 3. Vaultwarden bekommt sofort einen Code, und Audiobookshelf ebenfalls, ohne neue Eingabe
        for (OidcTestClient client : List.of(vault, audiobookshelf)) {
            Browser.Result r = authorize(b, client);
            assertThat(r.status()).isEqualTo(302);
            assertThat(r.location()).startsWith(OidcTestClient.REDIRECT_URI);
            assertThat(OidcTestClient.queryOf(r.location())).containsKey("code");
        }
    }

    @Test
    void ssoSessionIsTheSameOneTheHubUsesAndEndsWithSigningOut() throws Exception {
        setUpVaultAndJoshua();
        setUpSecondService();
        Browser b = loggedIn("joshua");                       // Anmeldung im Hub
        assertThat(b.get("/api/hub").ok()).isTrue();

        assertThat(authorize(b, audiobookshelf).location()).startsWith(OidcTestClient.REDIRECT_URI);

        assertThat(b.post("/api/auth/logout", Map.of()).ok()).isTrue();
        // Nach dem Abmelden ist die Anmeldung beim Provider weg: Die Dienste fragen wieder nach
        assertThat(authorize(b, vault).location()).contains("/login?continue=");
        assertThat(authorize(b, audiobookshelf).location()).contains("/login?continue=");
    }

    @Test
    void ssoNeverSkipsTheAccessCheckOfASpecificService() throws Exception {
        setUpVaultAndJoshua();
        setUpSecondService();
        permissions.revokeFromUser(joshua, "service.abs");
        Browser b = loggedIn("joshua");
        assertThat(authorize(b, vault).location()).startsWith(OidcTestClient.REDIRECT_URI);
        // Angemeldet heißt nicht berechtigt: Für Audiobookshelf gibt es keinen Code
        Browser.Result denied = authorize(b, audiobookshelf);
        assertThat(denied.location()).contains("/access-denied").doesNotContain("code=");
    }

    @Test
    void aBannedUserGetsNoCodeFromAnyService() throws Exception {
        setUpVaultAndJoshua();
        setUpSecondService();
        Browser b = loggedIn("joshua");
        jdbc.sql("UPDATE users SET banned = true WHERE id = :i").param("i", joshua).update();
        jdbc.sql("DELETE FROM login_sessions").update();
        for (OidcTestClient client : List.of(vault, audiobookshelf)) {
            assertThat(authorize(b, client).location()).doesNotContain("code=");
        }
    }
}
