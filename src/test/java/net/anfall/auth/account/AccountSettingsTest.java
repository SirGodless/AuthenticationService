package net.anfall.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.AdminTestBase;
import net.anfall.auth.testsupport.Browser;
import org.junit.jupiter.api.Test;

/** Sprache und Darstellung des eigenen Kontos ändern. */
class AccountSettingsTest extends AdminTestBase {

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

    @Test
    void theThemeDefaultsToSystemAndCanBeChanged() throws Exception {
        Session s = loggedInWithPermissions("anna", false);
        assertThat(json.readTree(s.browser().get("/api/hub").body()).get("account").get("theme").asString()).isEqualTo("system");
        for (String theme : new String[]{"dark", "light", "system"}) {
            assertThat(s.browser().put("/api/auth/theme", Map.of("theme", theme)).ok()).isTrue();
            assertThat(json.readTree(s.browser().get("/api/hub").body()).get("account").get("theme").asString()).isEqualTo(theme);
        }
    }

    @Test
    void invalidThemesAndMissingLoginAreRefused() throws Exception {
        Session s = loggedInWithPermissions("anna", false);
        for (String bad : new String[]{"blue", "", "DARK", "dark; x"}) {
            assertThat(s.browser().put("/api/auth/theme", Map.of("theme", bad)).body()).contains("theme_invalid");
        }
        assertThat(browser().put("/api/auth/theme", Map.of("theme", "dark")).status()).isEqualTo(401);
    }
}
