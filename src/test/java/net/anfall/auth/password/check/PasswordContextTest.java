package net.anfall.auth.password.check;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Kontextwörter (NIST SP 800-63B-4, 3.1.1.2): Es zählt das GANZE Passwort, keine Teile davon. */
class PasswordContextTest {

    private final PasswordContext context = PasswordContext.of("joshua", "joshua.schluck@example.com")
            .plus(List.of("anfall.net", "userhub"));

    @Test
    void passwordMadeOnlyOfAContextWordIsBlocked_evenRepeatedAndDecorated() {
        assertThat(context.isMadeOfContextWords("joshuajoshuajoshua")).isTrue();
        assertThat(context.isMadeOfContextWords("Joshua-Joshua-2026-JOSHUA")).isTrue();     // Ziffern, Trennzeichen, Groß/Klein
        assertThat(context.isMadeOfContextWords("joshua.schluck@example.com")).isTrue();   // ganze E-Mail
        assertThat(context.isMadeOfContextWords("joshua.schluck joshua.schluck")).isTrue(); // Teil vor dem @, doppelt
        assertThat(context.isMadeOfContextWords("anfall.net anfall.net")).isTrue();         // Dienstname
        assertThat(context.isMadeOfContextWords("UserHub-UserHub-UserHub")).isTrue();
    }

    @Test
    void passwordThatOnlyContainsAContextWordIsAllowed() {
        // NIST: nicht Teile oder enthaltene Wörter vergleichen
        assertThat(context.isMadeOfContextWords("joshua loves a long passphrase")).isFalse();
        assertThat(context.isMadeOfContextWords("my userhub is the best place")).isFalse();
        assertThat(context.isMadeOfContextWords("correct horse battery staple")).isFalse();
    }

    @Test
    void emptyOrDigitOnlyPasswordsAndMissingContextNeverMatch() {
        assertThat(context.isMadeOfContextWords("")).isFalse();
        assertThat(context.isMadeOfContextWords("1234567890123456")).isFalse();
        assertThat(PasswordContext.NONE.isMadeOfContextWords("joshuajoshuajoshua")).isFalse();
        assertThat(PasswordContext.of(null, null).isMadeOfContextWords("whatever long text here")).isFalse();
    }

    @Test
    void veryShortContextWordsAreIgnored() {
        // "ab" wiederholt wäre ein Zufallstreffer-Risiko ohne Nutzen
        assertThat(PasswordContext.of("ab", "ab@x.de").isMadeOfContextWords("abababababababab")).isFalse();
    }
}
