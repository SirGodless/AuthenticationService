package net.anfall.auth.invite;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.web.ApiException;
import net.anfall.auth.web.ClientIp;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sperre pro IP für den Link-Endpunkt (CLAUDE.md Abschnitt 4): Wer mehr als 3-mal pro Stunde einen Link öffnet,
 * den es nie gab, wird für einen Tag gesperrt. Nur der Link-Endpunkt ist betroffen, nicht der Login. IPv6 zählt pro /64.
 * Abgelaufene, benutzte und deaktivierte Links zählen nicht (sonst sperrt sich ein Nutzer selbst).
 */
@Service
public class InviteIpBlockService {

    static final int MAX_BAD_PER_WINDOW = 3;
    static final Duration WINDOW = Duration.ofHours(1);
    static final Duration BLOCK = Duration.ofDays(1);

    public record Block(String ipKey, Instant blockedUntil, Instant blockedAt) {
    }

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public InviteIpBlockService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Wirft 429, wenn diese Adresse gesperrt ist. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireNotBlocked(String ip) {
        boolean blocked = jdbc.sql("SELECT EXISTS (SELECT 1 FROM invite_ip_blocks WHERE ip_key = :k AND blocked_until > now())")
                .param("k", ClientIp.rateKey(ip)).query(Boolean.class).single();
        if (blocked) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ip_blocked");
        }
    }

    /** Zählt einen Link, den es nie gab. Beim vierten innerhalb einer Stunde beginnt die Sperre. Bleibt auch erhalten, wenn die Anfrage danach abbricht. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnknownLink(String ip) {
        String key = ClientIp.rateKey(ip);
        Integer count = jdbc.sql("""
                        INSERT INTO invite_ip_blocks (ip_key, fail_count, window_start) VALUES (:k, 1, now())
                        ON CONFLICT (ip_key) DO UPDATE SET
                            fail_count = CASE WHEN invite_ip_blocks.window_start < now() - make_interval(secs => :w)
                                              THEN 1 ELSE invite_ip_blocks.fail_count + 1 END,
                            window_start = CASE WHEN invite_ip_blocks.window_start < now() - make_interval(secs => :w)
                                                THEN now() ELSE invite_ip_blocks.window_start END
                        RETURNING fail_count
                        """).param("k", key).param("w", (double) WINDOW.toSeconds()).query(Integer.class).single();
        if (count > MAX_BAD_PER_WINDOW) {
            jdbc.sql("""
                            UPDATE invite_ip_blocks SET blocked_until = now() + make_interval(secs => :b), blocked_at = now(),
                                                        fail_count = 0 WHERE ip_key = :k
                            """).param("b", (double) BLOCK.toSeconds()).param("k", key).update();
            audit.recordNow(null, null, "invite.ip_blocked", Map.of(), ip);
        }
    }

    public List<Block> activeBlocks() {
        return jdbc.sql("SELECT ip_key, blocked_until, blocked_at FROM invite_ip_blocks WHERE blocked_until > now() ORDER BY blocked_at DESC")
                .query((rs, n) -> new Block(rs.getString("ip_key"), rs.getTimestamp("blocked_until").toInstant(),
                        rs.getTimestamp("blocked_at").toInstant())).list();
    }

    /** Ein Admin hebt die Sperre auf. */
    @Transactional
    public void lift(UUID actor, String ipKey, String ip) {
        int n = jdbc.sql("DELETE FROM invite_ip_blocks WHERE ip_key = :k").param("k", ipKey).update();
        if (n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "block_unknown");
        }
        audit.record(actor, null, "invite.ip_block_lifted", Map.of(), ip);
    }

    /** Alte Zähler aufräumen (Sperre abgelaufen und Fenster lange vorbei). */
    public int deleteOld() {
        return jdbc.sql("""
                        DELETE FROM invite_ip_blocks WHERE window_start < now() - interval '2 days'
                                                       AND (blocked_until IS NULL OR blocked_until < now())
                        """).update();
    }
}
