package net.anfall.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import com.nimbusds.jose.jwk.JWKSet;
import net.anfall.auth.testsupport.OidcTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Rotation der Signierschlüssel: Mehrere Schlüssel im JWKS, alte Tokens bleiben prüfbar, neue nutzen den neuen Schlüssel. */
class SigningKeyRotationTest extends OidcTestBase {

    @Autowired SigningKeyService keys;

    @BeforeEach
    void setUp() {
        setUpVaultAndJoshua();
    }

    @Test
    void afterRotationOldTokensStillVerifyAndNewTokensUseTheNewKey() throws Exception {
        OidcTestClient.TokenResponse before = fullLogin(loggedIn("joshua"));
        String oldKid = OidcTestClient.keyIdOf(before.accessToken());

        String newKid = keys.rotate();

        JWKSet set = vault.jwks();
        assertThat(set.getKeys()).extracting(k -> k.getKeyID()).containsExactlyInAnyOrder(oldKid, newKid);
        assertThat(set.getKeys()).noneMatch(k -> k.isPrivate());
        assertThat(vault.verify(before.idToken()).getSubject()).isEqualTo(joshua.toString());   // alter Schlüssel prüft noch

        OidcTestClient.TokenResponse after = vault.refresh(before.refreshToken());
        assertThat(OidcTestClient.keyIdOf(after.accessToken())).isEqualTo(newKid);
        assertThat(OidcTestClient.keyIdOf(after.idToken())).isEqualTo(newKid);
    }

    @Test
    void theRetiredKeyLosesItsPrivatePartAtOnceAndIsDeletedAfterTheGracePeriod() throws Exception {
        String oldKid = jdbc.sql("SELECT kid FROM oidc_signing_keys WHERE retired_at IS NULL").query(String.class).single();
        keys.rotate();

        assertThat(jdbc.sql("SELECT private_key_enc IS NULL FROM oidc_signing_keys WHERE kid = :k").param("k", oldKid)
                .query(Boolean.class).single()).isTrue();
        assertThat(keys.deleteExpiredRetiredKeys()).isZero();           // Frist (2 Tage) läuft noch

        jdbc.sql("UPDATE oidc_signing_keys SET retired_at = now() - interval '3 days' WHERE kid = :k")
                .param("k", oldKid).update();
        assertThat(keys.deleteExpiredRetiredKeys()).isEqualTo(1);
        assertThat(vault.jwks().getKeys()).hasSize(1);
    }

    @Test
    void thereIsExactlyOneActiveKeyAndThePrivateKeyIsStoredEncrypted() {
        keys.rotate();
        keys.rotate();
        assertThat(jdbc.sql("SELECT count(*) FROM oidc_signing_keys WHERE retired_at IS NULL").query(Long.class).single())
                .isEqualTo(1);
        byte[] stored = jdbc.sql("SELECT private_key_enc FROM oidc_signing_keys WHERE retired_at IS NULL")
                .query(byte[].class).single();
        // Ein unverschlüsselter PKCS#8-Schlüssel beginnt mit 0x30 0x82 (DER-Sequenz), verschlüsselt mit zufälligem IV nicht
        assertThat(stored[0] == 0x30 && stored[1] == (byte) 0x82).isFalse();
    }

    @Test
    void ensureActiveKeyCreatesAKeyOnlyIfNoneExists() {
        String kid = jdbc.sql("SELECT kid FROM oidc_signing_keys WHERE retired_at IS NULL").query(String.class).single();
        keys.ensureActiveKey();
        assertThat(jdbc.sql("SELECT kid FROM oidc_signing_keys WHERE retired_at IS NULL").query(String.class).single())
                .isEqualTo(kid);
    }
}
