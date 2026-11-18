package net.anfall.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** 2FA selbst einrichten, Wiederherstellungscodes neu erzeugen, abschalten (nur ohne Verwaltungsrechte). */
class TwoFactorSettingsTest extends AdminTestBase {

    private Session plainUser() throws Exception {
        return loggedInWithPermissions("anna", false);
    }

    private byte[] pendingSecret(UUID id) {
        byte[] enc = jdbc.sql("SELECT secret_enc FROM totp_enrollments WHERE user_id = :u").param("u", id)
                .query(byte[].class).single();
        return cipher.decrypt(enc);
    }

    /** Richtet 2FA über die API ein und gibt Geheimnis und Wiederherstellungscodes zurück. */
    private record Enrolled(byte[] secret, List<String> codes, long step) {
    }

    private Enrolled enroll(Session s) throws Exception {
        assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", PASSWORD)).ok()).isTrue();
        byte[] secret = pendingSecret(s.id());
        long step = Totp.stepAt(System.currentTimeMillis());
        Browser.Result r = s.browser().post("/api/auth/2fa/enrollment/confirm", Map.of("code", Totp.codeForStep(secret, step)));
        assertThat(r.ok()).as(r.body()).isTrue();
        var codes = json.readTree(r.body()).get("recoveryCodes").valueStream().map(n -> n.asString()).toList();
        return new Enrolled(secret, codes, step);
    }

    @Test
    void statusShowsWhetherTwoFactorIsOnAndHowManyCodesAreLeft() throws Exception {
        Session s = plainUser();
        assertThat(s.browser().get("/api/auth/2fa").body()).contains("\"enabled\":false", "\"mandatory\":false");
        enroll(s);
        assertThat(s.browser().get("/api/auth/2fa").body()).contains("\"enabled\":true", "\"recoveryCodesLeft\":10");
    }

    @Test
    void enrollmentNeedsThePasswordAndAValidCode() throws Exception {
        Session s = plainUser();
        assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", "wrong wrong wrong wrong")).status()).isEqualTo(401);
        assertThat(s.browser().post("/api/auth/2fa/enrollment/confirm", Map.of("code", "123456")).body()).contains("totp_not_prepared");

        assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", PASSWORD)).body()).contains("secret", "issuer");
        assertThat(s.browser().post("/api/auth/2fa/enrollment/confirm", Map.of("code", "000000")).body()).contains("totp_code_invalid");
        // Ohne gültige Bestätigung ist die 2FA nicht aktiv
        assertThat(s.browser().get("/api/auth/2fa").body()).contains("\"enabled\":false");
    }

    @Test
    void enrollmentGivesTenRecoveryCodesAndStoresThemHashedOnly() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        assertThat(e.codes()).hasSize(10);
        List<String> stored = jdbc.sql("SELECT code_hash FROM recovery_codes WHERE user_id = :u").param("u", s.id())
                .query(String.class).list();
        assertThat(stored).hasSize(10).doesNotContainAnyElementsOf(e.codes());
        // Das Geheimnis liegt verschlüsselt in der Datenbank
        byte[] raw = jdbc.sql("SELECT secret_enc FROM user_totp WHERE user_id = :u").param("u", s.id()).query(byte[].class).single();
        assertThat(raw).isNotEqualTo(e.secret());
    }

    @Test
    void afterEnrollmentLoginAsksForTheSecondFactor() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        Browser b = browser();
        assertThat(login(b, "anna", PASSWORD).body()).contains("totp_required");
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(e.secret(), e.step() + 1))).ok()).isTrue();
    }

    @Test
    void enrollingTwiceIsRefused() throws Exception {
        Session s = plainUser();
        enroll(s);
        assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", PASSWORD)).status()).isEqualTo(409);
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        Browser.Result r = s.browser().post("/api/auth/2fa/recovery-codes",
                Map.of("password", PASSWORD, "code", Totp.codeForStep(e.secret(), e.step() + 1)));
        assertThat(r.ok()).as(r.body()).isTrue();
        List<String> fresh = json.readTree(r.body()).get("recoveryCodes").valueStream().map(n -> n.asString()).toList();
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(e.codes());
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u").param("u", s.id()).query(Long.class).single())
                .isEqualTo(10);
        // Ein alter Code gilt nicht mehr, ein neuer schon
        assertThat(s.browser().post("/api/auth/2fa/recovery-codes", Map.of("password", PASSWORD, "code", e.codes().get(0))).status())
                .isEqualTo(401);
        assertThat(s.browser().post("/api/auth/2fa/recovery-codes", Map.of("password", PASSWORD, "code", fresh.get(0))).ok()).isTrue();
    }

    @Test
    void regeneratingNeedsPasswordAndCode() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        String code = Totp.codeForStep(e.secret(), e.step() + 1);
        assertThat(s.browser().post("/api/auth/2fa/recovery-codes", Map.of("password", "wrong wrong wrong wrong", "code", code)).status())
                .isEqualTo(401);
        assertThat(s.browser().post("/api/auth/2fa/recovery-codes", Map.of("password", PASSWORD, "code", "000000")).status())
                .isEqualTo(401);
    }

    @Test
    void aUserWithoutAdminRightsCanTurnTwoFactorOffAndOtherSessionsEnd() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        Browser other = browser(); // zweite Sitzung desselben Users
        login(other, "anna", PASSWORD);
        // Zweiter Schritt mit einem Wiederherstellungscode, damit das Zeitfenster für den TOTP-Code frei bleibt
        other.post("/api/auth/login/second-factor", Map.of("code", e.codes().get(0)));
        assertThat(other.get("/api/auth/me").ok()).isTrue();

        Browser.Result r = s.browser().post("/api/auth/2fa/disable",
                Map.of("password", PASSWORD, "code", Totp.codeForStep(e.secret(), e.step() + 1)));
        assertThat(r.ok()).as(r.body()).isTrue();

        assertThat(s.browser().get("/api/auth/me").ok()).isTrue(); // die aktuelle Sitzung bleibt
        assertThat(other.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(s.browser().get("/api/auth/2fa").body()).contains("\"enabled\":false");
        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE user_id = :u").param("u", s.id()).query(Long.class).single()).isZero();
    }

    @Test
    void aUserWithAdminRightsCannotTurnTwoFactorOff() throws Exception {
        Session admin = loggedInAdmin("joshua");
        Browser.Result r = admin.browser().post("/api/auth/2fa/disable", Map.of("password", PASSWORD,
                "code", Totp.codeForStep(admin.totpSecret(), Totp.stepAt(System.currentTimeMillis()) + 1)));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).contains("two_factor_required_for_admins");
        assertThat(jdbc.sql("SELECT count(*) FROM user_totp WHERE user_id = :u").param("u", admin.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anAdminRightGrantedLaterMakesTheStatusMandatory() throws Exception {
        Session s = plainUser();
        enroll(s);
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key) VALUES (:u, 'admin.audit.view')").param("u", s.id()).update();
        assertThat(s.browser().get("/api/auth/2fa").body()).contains("\"mandatory\":true");
    }

    @Test
    void tooManyWrongAttemptsLockTheSettings() throws Exception {
        Session s = plainUser();
        for (int i = 0; i < 5; i++) {
            assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", "wrong wrong wrong wrong")).status()).isEqualTo(401);
        }
        // Auch das richtige Passwort wird nun abgewiesen
        assertThat(s.browser().post("/api/auth/2fa/enrollment", Map.of("password", PASSWORD)).status()).isEqualTo(429);
    }

    @Test
    void theApiNeedsALogin() throws Exception {
        assertThat(browser().get("/api/auth/2fa").status()).isEqualTo(401);
        assertThat(browser().post("/api/auth/2fa/disable", Map.of("password", "x", "code", "1")).status()).isEqualTo(401);
    }

    @Test
    void settingsChangesAreWrittenToTheAuditLogWithoutSecrets() throws Exception {
        Session s = plainUser();
        Enrolled e = enroll(s);
        String all = jdbc.sql("SELECT action || ' ' || details::text FROM audit_log WHERE action LIKE '2fa.%'")
                .query(String.class).list().toString();
        assertThat(all).contains("2fa.enabled");
        for (String code : e.codes()) {
            assertThat(all).doesNotContain(code);
        }
    }
}
