package net.anfall.auth.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** Protokoll: nur anhängen, IP nach 90 Tagen leeren, Einträge bei Fehlern behalten. */
class AuditLogTest extends IntegrationTestBase {

    @Autowired AuditLog audit;
    @Autowired AuditIpRetentionJob retention;

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

        assertThat(retention.run()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT ip FROM audit_log WHERE id = :i").param("i", old).query(String.class).optional())
                .isEmpty();
        assertThat(jdbc.sql("SELECT ip FROM audit_log WHERE id = :i").param("i", recent).query(String.class).single())
                .isEqualTo("203.0.113.2");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE id IN (:a, :b, :c)")
                .param("a", old).param("b", recent).param("c", noIp).query(Long.class).single()).isEqualTo(3);
        assertThat(retention.run()).isZero(); // zweiter Lauf ändert nichts
    }

    @Test
    void recordNowKeepsTheEntryEvenIfTheCallerRollsBack() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        audit.recordNow(id, id, "test.failure", Map.of(), "203.0.113.1");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'test.failure'").query(Long.class).single())
                .isEqualTo(1);
    }
}
