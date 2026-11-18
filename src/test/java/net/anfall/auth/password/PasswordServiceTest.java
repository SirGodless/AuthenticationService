package net.anfall.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Base64;
import java.util.Set;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.password.check.PasswordContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordServiceTest {

    private static final String PEPPER = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final PasswordContext ACCOUNT = PasswordContext.of("joshua", "joshua@example.com");

    // OWASP-Minimum, damit der Test schnell bleibt
    private final PasswordService service = new PasswordService(new Argon2PasswordEncoder(16, 32, 1, 19456, 2),
            p -> Set.of("password1234567890", "ÄÖÜ-geheim-passwort").contains(p),
            new AppProperties("https://auth.anfall.net", "k", PEPPER, "o", "anfall.net", null, null, null,
                    "no-reply@anfall.net", null, null, null, null, null));

    @Test
    void lengthBlocklistAndContextAreChecked() {
        assertThat(service.check("kurz", ACCOUNT)).isEqualTo("password_too_short");
        assertThat(service.check("a".repeat(129), ACCOUNT)).isEqualTo("password_too_long");
        assertThat(service.check("password1234567890", ACCOUNT)).isEqualTo("password_blocked");
        assertThat(service.check("joshua-joshua-joshua", ACCOUNT)).isEqualTo("password_context");
        assertThat(service.check("keine bekannte passphrase", ACCOUNT)).isNull();
    }

    @Test
    void theServiceNameIsAContextWordToo() {
        assertThat(service.check("anfall.net anfall.net", ACCOUNT)).isEqualTo("password_context");
        assertThat(service.check("auth.anfall.net-auth.anfall.net", ACCOUNT)).isEqualTo("password_context");
        assertThat(service.check("UserHub UserHub UserHub", ACCOUNT)).isEqualTo("password_context");
    }

    @Test
    void wholePasswordIsComparedNotParts() {
        // Ein Teil des Passworts auf der Liste ist kein Grund zur Ablehnung (NIST: nicht Teilstrings)
        assertThat(service.check("xx-password1234567890-xx", ACCOUNT)).isNull();
        assertThat(service.check("password1234567890 und mehr", ACCOUNT)).isNull();
        assertThat(service.check("my joshua is a long phrase", ACCOUNT)).isNull();
    }

    @Test
    void passwordsAreNeverTrimmedOrChanged() {
        // Leerzeichen am Rand gehören zum Passwort und zählen mit (15 Stück sind genug)
        assertThat(service.check(" ".repeat(15), ACCOUNT)).isNull();
        String hash = service.hash("  lang genug mit Rand  ");
        assertThat(service.matches("  lang genug mit Rand  ", hash)).isTrue();
        assertThat(service.matches("lang genug mit Rand", hash)).isFalse();
    }

    @Test
    void hashIsSaltedAndMatchesOnlyTheRightPassword() {
        String hash = service.hash("some long passphrase");
        assertThat(hash).isNotEqualTo(service.hash("some long passphrase")); // zufälliges Salz
        assertThat(service.matches("some long passphrase", hash)).isTrue();
        assertThat(service.matches("some long passphrasE", hash)).isFalse();
    }

    @Test
    void storedHashIsEncryptedWithThePepper_soADatabaseDumpAloneShowsNoArgon2Hash() {
        String hash = service.hash("some long passphrase");
        assertThat(hash).startsWith("v1:").doesNotContain("argon2").doesNotContain("$");
    }

    @Test
    void withAnotherPepperKeyThePasswordDoesNotMatch_andNothingThrows() {
        String hash = service.hash("some long passphrase");
        String otherKey = Base64.getEncoder().encodeToString("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".getBytes());
        PasswordService other = new PasswordService(new Argon2PasswordEncoder(16, 32, 1, 19456, 2), p -> false,
                new AppProperties("https://auth.anfall.net", "k", otherKey, "o", "anfall.net", null, null, null,
                        "no-reply@anfall.net", null, null, null, null, null));
        assertThat(other.matches("some long passphrase", hash)).isFalse();
    }

    @Test
    void unknownOrDamagedHashFormatsNeverMatch() {
        assertThat(service.matches("x", null)).isFalse();
        assertThat(service.matches("x", "$argon2id$v=19$m=19456,t=2,p=1$abc$def")).isFalse(); // ohne Pepper gespeichert
        assertThat(service.matches("x", "v1:!!!nicht-base64!!!")).isFalse();
        assertThat(service.matches("x", "v1:" + Base64.getEncoder().encodeToString(new byte[40]))).isFalse();
    }

    @Test
    void unicodeIsNormalizedToNfcForHashingAndBlocklist() {
        // "é" als ein Zeichen (NFC) und als e + Akzent (NFD) ergibt dasselbe Passwort
        String composed = "café und noch mehr text";
        String decomposed = "café und noch mehr text";
        assertThat(service.matches(decomposed, service.hash(composed))).isTrue();
        // Auch die Sperrliste sieht die normalisierte Form: "ÄÖÜ" als zerlegte Zeichen trifft den Eintrag
        assertThat(service.check("ÄÖÜ-geheim-passwort", ACCOUNT)).isEqualTo("password_blocked");
    }

    @Test
    void compatibilityCharactersAreNotFoldedBecauseNistAsksForNfcNotNfkc() {
        // Volle Breite (Ａ) bleibt ein anderes Zeichen als A: NFC faltet keine Kompatibilitätszeichen
        String hash = service.hash("Ａbcdefghijklmnopq");
        assertThat(service.matches("Abcdefghijklmnopq", hash)).isFalse();
        assertThat(service.matches("Ａbcdefghijklmnopq", hash)).isTrue();
    }

    @Test
    void dummyMatchNeverThrows() {
        service.dummyMatch("whatever");
        service.dummyMatch(null);
    }
}
