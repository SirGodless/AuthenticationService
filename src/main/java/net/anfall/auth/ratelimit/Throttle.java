package net.anfall.auth.ratelimit;

import java.time.Duration;
import net.anfall.auth.crypto.Tokens;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zähler und Sperren in der Datenbank (überleben einen Neustart, funktionieren auch mit mehreren Instanzen).
 * Jeder Aufruf läuft in einer eigenen Transaktion: Ein Fehlversuch muss gezählt bleiben, auch wenn die
 * aufrufende Anfrage danach mit einem Fehler abbricht.
 */
@Component
public class Throttle {

    private final JdbcClient jdbc;

    public Throttle(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Zählt eine Anfrage (egal ob erfolgreich) und sagt, ob sie noch im Limit liegt.
     * Festes Zeitfenster: Das Fenster beginnt bei der ersten Anfrage.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String bucket, String key, int max, Duration window) {
        return bump(bucket, key, window) <= max;
    }

    /** Ist dieser Schlüssel gerade gesperrt? */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isLocked(String bucket, String key) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM throttle
                                       WHERE bucket = :b AND key_hash = :k AND locked_until > now())
                        """)
                .param("b", bucket).param("k", Tokens.sha256Hex(key))
                .query(Boolean.class).single();
    }

    /** Zählt einen Fehlversuch. Ab max Fehlversuchen im Fenster beginnt die Pause. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String bucket, String key, int max, Duration window, Duration lock) {
        int count = bump(bucket, key, window);
        if (count >= max) {
            jdbc.sql("""
                            UPDATE throttle SET locked_until = now() + make_interval(secs => :lock),
                                                count = 0, window_start = now()
                            WHERE bucket = :b AND key_hash = :k
                            """)
                    .param("lock", (double) lock.toSeconds())
                    .param("b", bucket).param("k", Tokens.sha256Hex(key))
                    .update();
        }
    }

    /** Nach Erfolg den Zähler löschen. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String bucket, String key) {
        jdbc.sql("DELETE FROM throttle WHERE bucket = :b AND key_hash = :k")
                .param("b", bucket).param("k", Tokens.sha256Hex(key)).update();
    }

    /** Zähler erhöhen. Beginnt ein neues Fenster, wenn das alte abgelaufen ist. Atomar in einem Statement. */
    private int bump(String bucket, String key, Duration window) {
        return jdbc.sql("""
                        INSERT INTO throttle (bucket, key_hash, count, window_start)
                        VALUES (:b, :k, 1, now())
                        ON CONFLICT (bucket, key_hash) DO UPDATE SET
                            count = CASE WHEN throttle.window_start < now() - make_interval(secs => :w)
                                         THEN 1 ELSE throttle.count + 1 END,
                            window_start = CASE WHEN throttle.window_start < now() - make_interval(secs => :w)
                                                THEN now() ELSE throttle.window_start END
                        RETURNING count
                        """)
                .param("b", bucket).param("k", Tokens.sha256Hex(key)).param("w", (double) window.toSeconds())
                .query(Integer.class).single();
    }
}
