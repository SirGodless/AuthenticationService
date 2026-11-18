package net.anfall.auth.login.session;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExpiredSessionsJobTest extends IntegrationTestBase {

    @Autowired ExpiredSessionsJob job;

    @Test
    void onlyExpiredSessionsAreDeleted() {
        UUID id = createUser("joshua", "joshua@example.com", PASSWORD, true);
        jdbc.sql("""
                INSERT INTO login_sessions (id_hash, user_id, expires_at, remember)
                VALUES ('a', :u, now() - interval '1 minute', false), ('b', :u, now() + interval '1 hour', false)
                """).param("u", id).update();

        assertThat(job.run()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT id_hash FROM login_sessions").query(String.class).list()).containsExactly("b");
    }
}
