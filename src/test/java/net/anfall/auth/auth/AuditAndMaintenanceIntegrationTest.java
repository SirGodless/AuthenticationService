package net.anfall.auth.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.IntegrationTestBase;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.crypto.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class AuditAndMaintenanceIntegrationTest extends IntegrationTestBase {

    @Autowired AuditLog audit;
    @Autowired MaintenanceJobs jobs;
    @Autowired Throttle throttle;

    private long insertAuditRow(String ip, String interval) {
        return jdbc.sql("INSERT INTO audit_log (at, action, ip) VALUES (now() - CAST(:i AS interval), 'test.action', :ip) "
                        + "RETURNING id").param("i", interval).param("ip", ip).query(Long.class).single();
    }

    // ---- Protokoll nur anhängen ----

    @Test
    void auditLogCannotBeDeletedOrRewritten() {
        long id = insertAuditRow("203.0.113.1", "1 day");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log WHERE id = :i").param("i", id).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("nur zum Anhängen");
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET action = 'x' WHERE id = :i").param("i", id).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET details = '{\"a\":1}' WHERE id = :i").param("i", id)
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET ip = '1.2.3.4' WHERE id = :i").param("i", id).update())
                .isInstanceOf(DataAccessException.class);
        // Nur das Leeren der IP ist erlaubt
        assertThat(jdbc.sql("UPDATE audit_log SET ip = NULL WHERE id = :i").param("i", id).update()).isEqualTo(1);
    }

    @Test
    void ipAddressesAreClearedAfter90DaysButTheEntryStays() {
        long old = insertAuditRow("203.0.113.1", "91 days");
        long recent = insertAuditRow("203.0.113.2", "89 days");
        long noIp = insertAuditRow(null, "200 days");

        assertThat(jobs.clearOldIpAddresses()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT ip FROM audit_log WHERE id = :i").param("i", old).query(String.class).optional())
                .isEmpty();
        assertThat(jdbc.sql("SELECT ip FROM audit_log WHERE id = :i").param("i", recent).query(String.class).single())
                .isEqualTo("203.0.113.2");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE id IN (:a, :b, :c)")
                .param("a", old).param("b", recent).param("c", noIp).query(Long.class).single()).isEqualTo(3);
        assertThat(jobs.clearOldIpAddresses()).isZero(); // zweiter Lauf ändert nichts
    }

    @Test
    void recordNowKeepsTheEntryEvenIfTheCallerRollsBack() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        audit.recordNow(id, id, "test.failure", Map.of(), "203.0.113.1");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'test.failure'").query(Long.class).single())
                .isEqualTo(1);
    }

    // ---- Aufräumen ----

    @Test
    void expiredSessionsThrottleRowsAndOldTokensAreRemoved() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        jdbc.sql("""
                INSERT INTO login_sessions (id_hash, user_id, expires_at, remember)
                VALUES ('a', :u, now() - interval '1 minute', false), ('b', :u, now() + interval '1 hour', false)
                """).param("u", id).update();
        jdbc.sql("""
                INSERT INTO throttle (bucket, key_hash, count, window_start, locked_until) VALUES
                  ('x', 'old', 1, now() - interval '2 days', NULL),
                  ('x', 'oldlocked', 0, now() - interval '2 days', now() + interval '5 minutes'),
                  ('x', 'fresh', 1, now(), NULL)
                """).update();
        jdbc.sql("""
                INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at, used_at) VALUES
                  (gen_random_uuid(), :u, 'password_reset', 'h1', now() - interval '8 days', NULL),
                  (gen_random_uuid(), :u, 'password_reset', 'h2', now() + interval '1 hour', now() - interval '8 days'),
                  (gen_random_uuid(), :u, 'password_reset', 'h3', now() + interval '1 hour', NULL)
                """).param("u", id).update();

        jobs.runAll();

        assertThat(jdbc.sql("SELECT id_hash FROM login_sessions").query(String.class).list()).containsExactly("b");
        assertThat(jdbc.sql("SELECT key_hash FROM throttle ORDER BY key_hash").query(String.class).list())
                .containsExactly("fresh", "oldlocked"); // eine aktive Sperre bleibt
        assertThat(jdbc.sql("SELECT token_hash FROM email_tokens").query(String.class).list()).containsExactly("h3");
    }

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
