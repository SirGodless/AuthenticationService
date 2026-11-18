package net.anfall.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** Sprache des eigenen Kontos ändern. */
class LanguageTest extends AdminTestBase {

    @Test
    void theLanguageCanBeChangedAndMailsFollow() throws Exception {
        Session s = loggedInWithPermissions("anna", false);
        assertThat(s.browser().put("/api/auth/language", Map.of("language", "de")).ok()).isTrue();
        assertThat(jdbc.sql("SELECT language FROM users WHERE id = :i").param("i", s.id()).query(String.class).single()).isEqualTo("de");
        browser().post("/api/auth/password-reset/request", Map.of("email", "anna@example.com"));
        assertThat(mails().get(mails().size() - 1).getSubject()).doesNotContain("Reset your password");
    }

    @Test
    void unknownLanguagesAndMissingLoginAreRefused() throws Exception {
        Session s = loggedInWithPermissions("anna", false);
        Browser.Result r = s.browser().put("/api/auth/language", Map.of("language", "fr"));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("language_invalid");
        assertThat(browser().put("/api/auth/language", Map.of("language", "de")).status()).isEqualTo(401);
    }
}
