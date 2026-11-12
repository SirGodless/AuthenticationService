package net.anfall.auth.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountRulesTest {

    @Test
    void passwordNeeds15CharsAndAllowsUpTo128WithoutCompositionRules() {
        assertThat(PasswordPolicy.check("a".repeat(14))).isEqualTo("password_too_short");
        assertThat(PasswordPolicy.check("a".repeat(15))).isNull();          // keine Zusammensetzungsregeln
        assertThat(PasswordPolicy.check("a".repeat(64))).isNull();          // 64 müssen erlaubt sein
        assertThat(PasswordPolicy.check("a".repeat(128))).isNull();
        assertThat(PasswordPolicy.check("a".repeat(129))).isEqualTo("password_too_long");
        assertThat(PasswordPolicy.check(null)).isEqualTo("password_too_short");
    }

    @Test
    void passwordLengthCountsCharactersNotUtf16Units() {
        // Ein Emoji ist 1 Zeichen, aber 2 UTF-16-Einheiten: 14 Emojis sind zu kurz, 15 reichen
        assertThat(PasswordPolicy.check("😀".repeat(14))).isEqualTo("password_too_short");
        assertThat(PasswordPolicy.check("😀".repeat(15))).isNull();
        assertThat(PasswordPolicy.check("äöü".repeat(5))).isNull();
    }

    @Test
    void usernameIsLowercasedAndLimitedToSafeCharacters() {
        assertThat(AccountFields.normalizeUsername("  Joshua.X ")).isEqualTo("joshua.x");
        assertThat(AccountFields.isValidUsername("joshua_x-1.2")).isTrue();
        assertThat(AccountFields.isValidUsername("ab")).isFalse();
        assertThat(AccountFields.isValidUsername("a".repeat(33))).isFalse();
        assertThat(AccountFields.isValidUsername("a".repeat(32))).isTrue();
        assertThat(AccountFields.isValidUsername("jösh")).isFalse();
        assertThat(AccountFields.isValidUsername("jоsh")).isFalse();       // kyrillisches о (Lookalike)
        assertThat(AccountFields.isValidUsername("jo sh")).isFalse();
        assertThat(AccountFields.isValidUsername("Josh")).isFalse();        // nur nach Normalisierung gültig
    }

    @Test
    void emailIsNormalizedAndLooselyValidated() {
        assertThat(AccountFields.normalizeEmail("  Foo@Example.COM ")).isEqualTo("foo@example.com");
        assertThat(AccountFields.isValidEmail("foo@example.com")).isTrue();
        assertThat(AccountFields.isValidEmail("foo@example")).isFalse();
        assertThat(AccountFields.isValidEmail("foo bar@example.com")).isFalse();
        assertThat(AccountFields.isValidEmail("@example.com")).isFalse();
        assertThat(AccountFields.isValidEmail("a@b.c".repeat(100))).isFalse();
    }

    @Test
    void languageIsEnOrDe() {
        assertThat(AccountFields.isValidLanguage("en")).isTrue();
        assertThat(AccountFields.isValidLanguage("de")).isTrue();
        assertThat(AccountFields.isValidLanguage("fr")).isFalse();
        assertThat(AccountFields.isValidLanguage(null)).isFalse();
    }
}
