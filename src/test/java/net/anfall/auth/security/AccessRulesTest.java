package net.anfall.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Was ohne Anmeldung zugänglich ist. */
class AccessRulesTest extends IntegrationTestBase {

    @Test
    void everythingExceptSetupIsClosed() throws Exception {
        int status = mvc.perform(get("/api/users")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }
}
