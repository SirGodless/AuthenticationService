package net.anfall.auth.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.anfall.auth.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordServiceTest {

    // OWASP-Minimum, damit der Test schnell bleibt
    private final PasswordService service = new PasswordService(new Argon2PasswordEncoder(16, 32, 1, 19456, 2),
            p -> Set.of("password1234567890", "ÄÖÜ-geheim-passwort").contains(p));

    @Test
    void policyAndBlocklistAreChecked() {
        assertThat(service.check("kurz")).isEqualTo("password_too_short");
        assertThat(service.check("a".repeat(129))).isEqualTo("password_too_long");
        assertThat(service.check("password1234567890")).isEqualTo("password_blocked");
        assertThat(service.check("keine bekannte passphrase")).isNull();
    }

    @Test
    void wholePasswordIsComparedNotParts() {
        // Ein Teil des Passworts auf der Liste ist kein Grund zur Ablehnung
        assertThat(service.check("xx-password1234567890-xx")).isNull();
        assertThat(service.check("password1234567890 und mehr")).isNull();
    }

    @Test
    void hashIsArgon2idWithOwaspParametersAndSalted() {
        String hash = service.hash("some long passphrase");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hash).isNotEqualTo(service.hash("some long passphrase")); // zufälliges Salz
        assertThat(service.matches("some long passphrase", hash)).isTrue();
        assertThat(service.matches("some long passphrasE", hash)).isFalse();
    }

    @Test
    void unicodeIsNormalizedForHashingAndBlocklist() {
        // "é" als ein Zeichen und als e + Akzent ergibt dasselbe Passwort
        String composed = "café und noch mehr text";
        String decomposed = "café und noch mehr text";
        assertThat(service.matches(decomposed, service.hash(composed))).isTrue();
        // Volle Breite (Ａ) wird zu normalem A
        assertThat(service.matches("Ａbcdefghijklmnopq", service.hash("Abcdefghijklmnopq"))).isTrue();
        // Normalisiert landet es auf der Liste
        assertThat(service.check("password１２３４５６７８９０"))
                .isEqualTo("password_blocked");
    }

    @Test
    void dummyMatchNeverThrows() {
        service.dummyMatch("whatever");
        service.dummyMatch(null);
    }

    // ---- Sperrliste aus Datei ----

    @Test
    void fileBlocklistLoadsOneEntryPerLineAndIgnoresBlankLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "alpha-password-123\n\n  beta-password-456  \n");
        FilePasswordBlocklist list = new FilePasswordBlocklist(props(file.toString()));
        assertThat(list.isBlocked("alpha-password-123")).isTrue();
        assertThat(list.isBlocked("beta-password-456")).isTrue();
        assertThat(list.isBlocked("alpha-password")).isFalse(); // nur das ganze Passwort
        assertThat(list.isBlocked("")).isFalse();
    }

    @Test
    void missingFileStopsTheStartWithAClearMessage(@TempDir Path dir) {
        assertThatThrownBy(() -> new FilePasswordBlocklist(props(dir.resolve("fehlt.txt").toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fetch-blocklist.sh");
    }

    private static AppProperties props(String blocklistFile) {
        return new AppProperties("https://x", "k", "i", null, null, blocklistFile, "no-reply@x", null, null, null, null);
    }
}
