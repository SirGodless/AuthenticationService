package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.service.ServiceRegistrationService;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.testsupport.OidcTestClient;
import org.springframework.beans.factory.annotation.Autowired;

/** Gemeinsame Hilfen der OIDC-Tests: Dienst anlegen, anmelden, den Ablauf bis zum Code durchlaufen. */
abstract class OidcTestBase extends IntegrationTestBase {

    protected static final String SCOPES = "openid profile email offline_access";

    @Autowired protected ServiceRegistrationService registration;
    @Autowired protected PermissionService permissions;

    protected OidcTestClient vault;
    protected UUID joshua;

    /** Legt den Dienst "vault" und den User "joshua" an. Der User hat das Recht auf den Dienst. */
    protected void setUpVaultAndJoshua() {
        var registered = registration.register("vault", "Vaultwarden", java.util.List.of(OidcTestClient.REDIRECT_URI));
        vault = new OidcTestClient(mvc, json, "vault", registered.clientSecret());
        joshua = createUser("joshua", "joshua@example.com", PASSWORD, true, "de");
        permissions.grantToUser(joshua, "service.vault");
    }

    protected Browser loggedIn(String username) throws Exception {
        Browser b = browser();
        assertThat(login(b, username, PASSWORD).ok()).isTrue();
        return b;
    }

    /** Durchläuft die Anmeldung beim Dienst bis zum Code und gibt Code und PKCE zurück. */
    protected record CodeGrant(String code, OidcTestClient.Pkce pkce) {
    }

    protected CodeGrant authorize(Browser browser) throws Exception {
        OidcTestClient.Pkce pkce = OidcTestClient.pkce();
        Browser.Result r = browser.get(vault.authorizeUri(pkce, "state-123", SCOPES));
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.location()).startsWith(OidcTestClient.REDIRECT_URI);
        Map<String, String> q = OidcTestClient.queryOf(r.location());
        assertThat(q).containsEntry("state", "state-123");
        return new CodeGrant(q.get("code"), pkce);
    }

    /** Komplette Anmeldung: Tokens eines angemeldeten Users für den Dienst. */
    protected OidcTestClient.TokenResponse fullLogin(Browser browser) throws Exception {
        CodeGrant grant = authorize(browser);
        OidcTestClient.TokenResponse tokens = vault.exchangeCode(grant.code(), grant.pkce().verifier());
        assertThat(tokens.status()).isEqualTo(200);
        return tokens;
    }
}
