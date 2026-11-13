package net.anfall.auth.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import net.anfall.auth.crypto.Tokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/** Der Setup-Link: Log, Neustart, Ablauf, einmalige Nutzung, neutrale Fehler. */
@ExtendWith(OutputCaptureExtension.class)
class SetupLinkTest extends SetupTestBase {

    @Autowired SetupLinkAnnouncer announcer;

    // ---- Start und Setup-Link ----

    @Test
    void startupWithoutAdminLogsASetupLinkWhoseHashIsStored(CapturedOutput output) throws Exception {
        announcer.run(null);

        String log = output.getOut();
        int idx = log.lastIndexOf("https://auth.example.test/setup/"); // der letzte, der erste stammt vom App-Start
        assertThat(idx).as("Setup-Link im Log").isGreaterThanOrEqualTo(0);
        String token = log.substring(idx + "https://auth.example.test/setup/".length()).split("\\s")[0];

        assertThat(token).hasSize(43); // 256 Bit, Base64url
        // gespeichert ist nur der Hash, nie der Token
        assertThat(jdbc.sql("SELECT token_hash FROM setup_tokens").query(String.class).list())
                .containsExactly(Tokens.sha256Hex(token));
        mvc.perform(get("/api/setup/" + token)).andExpect(status().isOk());
    }

    @Test
    void restartIssuesNewLinkAndInvalidatesTheOldOne() throws Exception {
        String first = setup.issueToken();
        String second = setup.issueToken();
        mvc.perform(get("/api/setup/" + first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/setup/" + second)).andExpect(status().isOk());
    }

    @Test
    void noLinkIsIssuedOnceAnAdminExists() throws Exception {
        createAdminViaSetup();
        announcer.run(null);
        assertThat(jdbc.sql("SELECT count(*) FROM setup_tokens WHERE used_at IS NULL").query(Long.class).single())
                .isZero();
    }

    @Test
    void setupWorksOnlyOnce() throws Exception {
        String token = setup.issueToken();
        completeOk(token, "joshua", prepareAndCode(token));

        // derselbe Link nochmal
        mvc.perform(post("/api/setup/" + token + "/complete").contentType(MediaType.APPLICATION_JSON)
                        .content(body("zweiter", "zweiter@example.com", PASSWORD, "en", "123456")))
                .andExpect(status().isNotFound());
        // ein frisch erzeugter Link ist wertlos, sobald ein Admin existiert
        String another = setup.issueToken();
        mvc.perform(get("/api/setup/" + another)).andExpect(status().isNotFound());
        mvc.perform(post("/api/setup/" + another + "/prepare")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM users").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void invalidExpiredAndUsedLinksAllGiveTheSameNeutralError() throws Exception {
        String expired = setup.issueToken();
        jdbc.sql("UPDATE setup_tokens SET expires_at = now() - interval '1 second'").update();
        String unknown = Tokens.newToken();

        String a = mvc.perform(get("/api/setup/" + unknown)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String b = mvc.perform(get("/api/setup/" + expired)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b).contains("invalid_link");

        // benutzt
        String used = setup.issueToken();
        completeOk(used, "joshua", prepareAndCode(used));
        String c = mvc.perform(get("/api/setup/" + used)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(c).isEqualTo(a);
    }
}
