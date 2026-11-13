package net.anfall.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Limits beim Login: 5 Fehlversuche je Konto, dann 15 Minuten Pause. */
class LoginLimitsTest extends IntegrationTestBase {

    // ---- Limits ----

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheCorrectPassword() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        for (int i = 0; i < 5; i++) {
            assertThat(login(browser(), "joshua", "wrong password " + i).status()).isEqualTo(401);
        }
        Browser.Result locked = login(browser(), "joshua", PASSWORD);
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.body()).contains("too_many_attempts");

        // Nach der Pause (15 Minuten) geht es wieder
        jdbc.sql("UPDATE throttle SET locked_until = now() - interval '1 second'").update();
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue();
    }

    @Test
    void lockAppliesToUnknownNamesToo_soItDoesNotRevealWhichAccountsExist() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(login(browser(), "niemand", "wrong password " + i).status()).isEqualTo(401);
        }
        assertThat(login(browser(), "niemand", "wrong password 6").status()).isEqualTo(429);
    }

    @Test
    void lockIsPerAccountAndAliasesShareIt() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        createUser("anna", "anna@example.com", PASSWORD, true);
        // Fehlversuche über Username und E-Mail zählen für dasselbe Konto
        for (int i = 0; i < 3; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        for (int i = 0; i < 2; i++) {
            login(browser(), "joshua@example.com", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).status()).isEqualTo(429);
        assertThat(login(browser(), "anna", PASSWORD).ok()).isTrue(); // anderes Konto unberührt
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        createUser("joshua", "joshua@example.com", PASSWORD, true);
        for (int i = 0; i < 4; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue();
        for (int i = 0; i < 4; i++) {
            login(browser(), "joshua", "wrong password " + i);
        }
        assertThat(login(browser(), "joshua", PASSWORD).ok()).isTrue(); // 4 + 4 Fehlversuche, aber nie 5 am Stück
    }
}
