package net.anfall.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AccountInputRulesTest {

    @Test
    void usernameIsLowercasedAndLimitedToSafeCharacters() {
        assertThat(AccountInputRules.normalizeUsername("  Joshua.X ")).isEqualTo("joshua.x");
        assertThat(AccountInputRules.isValidUsername("joshua_x-1.2")).isTrue();
        assertThat(AccountInputRules.isValidUsername("ab")).isFalse();
        assertThat(AccountInputRules.isValidUsername("a".repeat(33))).isFalse();
        assertThat(AccountInputRules.isValidUsername("a".repeat(32))).isTrue();
        assertThat(AccountInputRules.isValidUsername("jösh")).isFalse();
        assertThat(AccountInputRules.isValidUsername("jоsh")).isFalse();       // kyrillisches о (Lookalike)
        assertThat(AccountInputRules.isValidUsername("jo sh")).isFalse();
        assertThat(AccountInputRules.isValidUsername("Josh")).isFalse();        // nur nach Normalisierung gültig
    }

    @Test
    void emailIsNormalizedAndLooselyValidated() {
        assertThat(AccountInputRules.normalizeEmail("  Foo@Example.COM ")).isEqualTo("foo@example.com");
        assertThat(AccountInputRules.isValidEmail("foo@example.com")).isTrue();
        assertThat(AccountInputRules.isValidEmail("foo@example")).isFalse();
        assertThat(AccountInputRules.isValidEmail("foo bar@example.com")).isFalse();
        assertThat(AccountInputRules.isValidEmail("@example.com")).isFalse();
        assertThat(AccountInputRules.isValidEmail("a@b.c".repeat(100))).isFalse();
    }

    @Test
    void languageIsEnOrDe() {
        assertThat(AccountInputRules.isValidLanguage("en")).isTrue();
        assertThat(AccountInputRules.isValidLanguage("de")).isTrue();
        assertThat(AccountInputRules.isValidLanguage("fr")).isFalse();
        assertThat(AccountInputRules.isValidLanguage(null)).isFalse();
    }
}
