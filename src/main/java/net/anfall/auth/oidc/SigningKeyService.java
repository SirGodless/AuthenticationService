package net.anfall.auth.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.SecretCipher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signierschlüssel des Providers (RS256, RSA 2048). Mehrere Schlüssel gleichzeitig, damit man rotieren kann:
 * Der aktive Schlüssel signiert, ausgemusterte bleiben als öffentliche Schlüssel im JWKS, bis alle damit signierten
 * Tokens abgelaufen sind. Private Schlüssel liegen verschlüsselt in der Datenbank (Schlüssel aus der Umgebung).
 */
@Service
public class SigningKeyService {

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final AppProperties.Oidc config;

    public SigningKeyService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.cipher = new SecretCipher(props.oidcKeyEncryptionKey(), "OIDC_KEY_ENCRYPTION_KEY");
        this.config = props.oidc();
    }

    /** Macht einen neuen Schlüssel zum aktiven und mustert den bisherigen aus. Gibt die neue kid zurück. */
    @Transactional
    public String rotate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 16);
            RSAKey publicKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID(kid)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
            // Der alte Schlüssel verliert sofort den privaten Teil: Er kann nur noch prüfen, nicht mehr signieren.
            jdbc.sql("UPDATE oidc_signing_keys SET retired_at = now(), private_key_enc = NULL WHERE retired_at IS NULL")
                    .update();
            jdbc.sql("INSERT INTO oidc_signing_keys (kid, private_key_enc, public_jwk) VALUES (:k, :p, :j)")
                    .param("k", kid).param("p", cipher.encrypt(pair.getPrivate().getEncoded()))
                    .param("j", publicKey.toJSONString()).update();
            return kid;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Legt beim ersten Start den ersten Schlüssel an. */
    public void ensureActiveKey() {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM oidc_signing_keys WHERE retired_at IS NULL)")
                .query(Boolean.class).single();
        if (!exists) {
            try {
                rotate();
            } catch (DuplicateKeyException e) {
                // Ein anderer Start war schneller, das ist in Ordnung
            }
        }
    }

    /** Alle Schlüssel: der aktive mit privatem Teil (signiert), ausgemusterte nur öffentlich (prüfen). */
    public JWKSet loadJwkSet() {
        List<JWK> keys = new ArrayList<>();
        jdbc.sql("SELECT private_key_enc, public_jwk FROM oidc_signing_keys ORDER BY created_at DESC")
                .query((rs, n) -> new Object[]{rs.getBytes("private_key_enc"), rs.getString("public_jwk")})
                .list().forEach(row -> keys.add(toJwk((byte[]) row[0], (String) row[1])));
        return new JWKSet(keys);
    }

    /** Löscht ausgemusterte Schlüssel nach der Frist. */
    public int deleteExpiredRetiredKeys() {
        return jdbc.sql("DELETE FROM oidc_signing_keys WHERE retired_at < now() - make_interval(secs => :s)")
                .param("s", (double) config.retiredKeyGrace().toSeconds()).update();
    }

    private JWK toJwk(byte[] privateKeyEnc, String publicJwk) {
        try {
            RSAKey publicKey = (RSAKey) JWK.parse(publicJwk);
            if (privateKeyEnc == null) {
                return publicKey;
            }
            RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(cipher.decrypt(privateKeyEnc)));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        } catch (ParseException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Signierschlüssel nicht lesbar (falscher OIDC_KEY_ENCRYPTION_KEY?)", e);
        }
    }
}
