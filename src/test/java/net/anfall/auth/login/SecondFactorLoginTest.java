package net.anfall.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.twofactor.Totp;
import org.junit.jupiter.api.Test;

/** Zweiter Schritt der Anmeldung: TOTP-Code und Wiederherstellungscode. */
class SecondFactorLoginTest extends IntegrationTestBase {

    // ---- 2FA ----

    @Test
    void withTotpPasswordAloneIsNotEnough() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);

        Browser b = browser();
        Browser.Result step1 = login(b, "joshua", PASSWORD);
        assertThat(step1.body()).contains("totp_required");
        String pendingCookie = b.cookie(SID);
        // Wartende Sitzung ist kein Login
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(b.post("/api/auth/password", Map.of("currentPassword", PASSWORD, "newPassword", "x".repeat(20)))
                .status()).isEqualTo(401);

        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).ok()).isTrue();
        assertThat(b.get("/api/auth/me").ok()).isTrue();
        assertThat(b.cookie(SID)).isNotEqualTo(pendingCookie); // neue Sitzung (gegen Session Fixation)
        // Die alte wartende Sitzung ist weg
        Browser old = browser();
        old.setCookie(SID, pendingCookie);
        assertThat(old.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void wrongTotpCodeIsRejected() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        Browser.Result r = b.post("/api/auth/login/second-factor", Map.of("code", "000000"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).contains("invalid_code");
        assertThat(b.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void aTotpCodeWorksOnlyOnce() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        long step = Totp.stepAt(System.currentTimeMillis());

        Browser first = browser();
        login(first, "joshua", PASSWORD);
        assertThat(first.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step))).ok())
                .isTrue();

        Browser second = browser();
        login(second, "joshua", PASSWORD);
        // derselbe Code nochmal: abgelehnt
        assertThat(second.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step)))
                .status()).isEqualTo(401);
        // der Code des nächsten Zeitfensters geht
        assertThat(second.post("/api/auth/login/second-factor", Map.of("code", Totp.codeForStep(secret, step + 1)))
                .ok()).isTrue();
    }

    @Test
    void fiveWrongSecondFactorsLockEvenTheRightCode() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        for (int i = 0; i < 5; i++) {
            assertThat(b.post("/api/auth/login/second-factor", Map.of("code", "00000" + i)).status()).isEqualTo(401);
        }
        Browser.Result r = b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret)));
        assertThat(r.status()).isEqualTo(429);
        // Auch eine neue Anmeldung kommt nicht an der gesperrten 2FA vorbei
        Browser again = browser();
        login(again, "joshua", PASSWORD);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).status())
                .isEqualTo(429);
    }

    @Test
    void recoveryCodeWorksOnceAndIsFormatTolerant() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        enableTotp(id);
        List<String> codes = addRecoveryCodes(id);

        Browser b = browser();
        login(b, "joshua", PASSWORD);
        String sloppy = codes.get(0).toLowerCase().replace("-", " ");
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", sloppy)).ok()).isTrue();
        assertThat(b.get("/api/auth/me").ok()).isTrue();

        Browser again = browser();
        login(again, "joshua", PASSWORD);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", codes.get(0))).status()).isEqualTo(401);
        assertThat(again.post("/api/auth/login/second-factor", Map.of("code", codes.get(1))).ok()).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM recovery_codes WHERE used_at IS NOT NULL").query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void secondFactorWithoutPendingLoginIsRejected() throws Exception {
        assertThat(browser().post("/api/auth/login/second-factor", Map.of("code", "123456")).status())
                .isEqualTo(401);
    }

    @Test
    void pendingLoginExpiresAfterFiveMinutes() throws Exception {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        byte[] secret = enableTotp(id);
        Browser b = browser();
        login(b, "joshua", PASSWORD);
        assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint FROM login_sessions")
                .query(Long.class).single()).isBetween(295L, 305L);
        jdbc.sql("UPDATE login_sessions SET expires_at = now() - interval '1 second'").update();
        assertThat(b.post("/api/auth/login/second-factor", Map.of("code", totpNow(secret))).status()).isEqualTo(401);
    }
}
