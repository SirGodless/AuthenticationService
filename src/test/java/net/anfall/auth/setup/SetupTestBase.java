package net.anfall.auth.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.ArrayList;
import java.util.List;
import net.anfall.auth.testsupport.IntegrationTestBase;
import net.anfall.auth.twofactor.Totp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Gemeinsame Hilfen der Setup-Tests (Link holen, TOTP-Code berechnen, Formular absenden). */
class SetupTestBase extends IntegrationTestBase {

    @Autowired protected SetupTokenService setup;

    // ---- Hilfsmethoden ----

    protected List<String> createAdminViaSetup() throws Exception {
        String token = setup.issueToken();
        return completeOk(token, "joshua", prepareAndCode(token));
    }

    protected String prepare(String token) throws Exception {
        String res = mvc.perform(post("/api/setup/" + token + "/prepare")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("secret").asString();
    }

    protected String prepareAndCode(String token) throws Exception {
        return codeFor(prepare(token));
    }

    /** Berechnet den Code so, wie es die Authenticator-App täte (aus dem Base32-Geheimnis). */
    protected static String codeFor(String base32Secret) {
        byte[] secret = decodeBase32(base32Secret);
        return Totp.codeForStep(secret, Totp.stepAt(System.currentTimeMillis()));
    }

    protected List<String> completeOk(String token, String username, String code) throws Exception {
        String res = postComplete(token, username, code).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(res).get("recoveryCodes");
        List<String> codes = new ArrayList<>();
        node.forEach(n -> codes.add(n.asString()));
        return codes;
    }

    protected org.springframework.test.web.servlet.ResultActions postComplete(String token, String username, String code)
            throws Exception {
        return mvc.perform(post("/api/setup/" + token + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content(body(username, "Joshua@Example.com", PASSWORD, "de", code)));
    }

    protected void assertError(String token, String username, String email, String password, String lang, String code,
                             int expectedStatus, String expectedError) throws Exception {
        MvcResult r = mvc.perform(post("/api/setup/" + token + "/complete").contentType(MediaType.APPLICATION_JSON)
                .content(body(username, email, password, lang, code))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expectedStatus);
        assertThat(r.getResponse().getContentAsString()).contains(expectedError);
    }

    protected String body(String username, String email, String password, String language, String code) {
        return json.writeValueAsString(java.util.Map.of("username", username, "email", email,
                "password", password, "language", language, "totpCode", code));
    }

    protected static byte[] decodeBase32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
