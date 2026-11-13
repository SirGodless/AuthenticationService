package net.anfall.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Zähler und Sperren. */
class ThrottleTest extends IntegrationTestBase {

    @Autowired Throttle throttle;

    // ---- Zähler ----

    @Test
    void throttleCountsPerWindowAndStoresOnlyHashedKeys() {
        Duration hour = Duration.ofHours(1);
        assertThat(throttle.allow("t", "anna@example.com", 2, hour)).isTrue();
        assertThat(throttle.allow("t", "anna@example.com", 2, hour)).isTrue();
        assertThat(throttle.allow("t", "anna@example.com", 2, hour)).isFalse();
        assertThat(throttle.allow("t", "bert@example.com", 2, hour)).isTrue(); // anderer Schlüssel
        assertThat(throttle.allow("other", "anna@example.com", 2, hour)).isTrue(); // anderer Zähler

        // Neues Fenster, wenn das alte vorbei ist
        jdbc.sql("UPDATE throttle SET window_start = now() - interval '2 hours'").update();
        assertThat(throttle.allow("t", "anna@example.com", 2, hour)).isTrue();

        assertThat(jdbc.sql("SELECT string_agg(key_hash, ' ') FROM throttle").query(String.class).single())
                .doesNotContain("anna").contains(Tokens.sha256Hex("anna@example.com"));
    }

    @Test
    void failuresLockAfterTheLimitAndClearResets() {
        Duration window = Duration.ofMinutes(15);
        Duration lock = Duration.ofMinutes(15);
        for (int i = 0; i < 4; i++) {
            throttle.fail("login", "k", 5, window, lock);
        }
        assertThat(throttle.isLocked("login", "k")).isFalse();
        throttle.fail("login", "k", 5, window, lock);
        assertThat(throttle.isLocked("login", "k")).isTrue();
        assertThat(throttle.isLocked("login", "other")).isFalse();

        throttle.clear("login", "k");
        assertThat(throttle.isLocked("login", "k")).isFalse();
    }

    @Test
    void failuresOutsideTheWindowDoNotAddUp() {
        Duration window = Duration.ofMinutes(15);
        for (int i = 0; i < 4; i++) {
            throttle.fail("login", "k", 5, window, window);
        }
        jdbc.sql("UPDATE throttle SET window_start = now() - interval '16 minutes'").update();
        throttle.fail("login", "k", 5, window, window);
        assertThat(throttle.isLocked("login", "k")).isFalse();
    }
}
