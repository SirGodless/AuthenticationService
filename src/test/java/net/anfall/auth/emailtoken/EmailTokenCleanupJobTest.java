package net.anfall.auth.emailtoken;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EmailTokenCleanupJobTest extends IntegrationTestBase {

    @Autowired EmailTokenCleanupJob job;

    @Test
    void tokensExpiredOrUsedMoreThanSevenDaysAgoAreDeleted() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        jdbc.sql("""
                INSERT INTO email_tokens (id, user_id, purpose, token_hash, expires_at, used_at) VALUES
                  (gen_random_uuid(), :u, 'password_reset', 'h1', now() - interval '8 days', NULL),
                  (gen_random_uuid(), :u, 'password_reset', 'h2', now() + interval '1 hour', now() - interval '8 days'),
                  (gen_random_uuid(), :u, 'password_reset', 'h3', now() + interval '1 hour', NULL)
                """).param("u", id).update();

        assertThat(job.run()).isEqualTo(2);

        assertThat(jdbc.sql("SELECT token_hash FROM email_tokens").query(String.class).list()).containsExactly("h3");
    }
}
