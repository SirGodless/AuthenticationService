package net.anfall.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ThrottleCleanupJobTest extends IntegrationTestBase {

    @Autowired ThrottleCleanupJob job;

    @Test
    void oldCountersAreRemovedButActiveLocksStay() {
        jdbc.sql("""
                INSERT INTO throttle (bucket, key_hash, count, window_start, locked_until) VALUES
                  ('x', 'old', 1, now() - interval '2 days', NULL),
                  ('x', 'oldlocked', 0, now() - interval '2 days', now() + interval '5 minutes'),
                  ('x', 'fresh', 1, now(), NULL)
                """).update();

        assertThat(job.run()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT key_hash FROM throttle ORDER BY key_hash").query(String.class).list())
                .containsExactly("fresh", "oldlocked");
    }
}
