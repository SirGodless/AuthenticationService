package net.anfall.auth.password.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import net.anfall.auth.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePasswordBlocklistTest {

    @Test
    void loadsOneEntryPerLineAndIgnoresBlankLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "alpha-password-123\n\n  beta-password-456  \n");
        FilePasswordBlocklist list = new FilePasswordBlocklist(props(file.toString()));
        assertThat(list.isBlocked("alpha-password-123")).isTrue();
        assertThat(list.isBlocked("beta-password-456")).isTrue();
        assertThat(list.isBlocked("alpha-password")).isFalse(); // nur das ganze Passwort
        assertThat(list.isBlocked("")).isFalse();
    }

    @Test
    void capitalizationDoesNotHelp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("list.txt");
        Files.writeString(file, "password1234567890\nMixedCase-Entry-123\n");
        FilePasswordBlocklist list = new FilePasswordBlocklist(props(file.toString()));
        assertThat(list.isBlocked("Password1234567890")).isTrue();
        assertThat(list.isBlocked("PASSWORD1234567890")).isTrue();
        assertThat(list.isBlocked("mixedcase-entry-123")).isTrue();
        assertThat(list.isBlocked("MixedCase-Entry-123")).isTrue();
    }

    @Test
    void missingFileStopsTheStartWithAClearMessage(@TempDir Path dir) {
        assertThatThrownBy(() -> new FilePasswordBlocklist(props(dir.resolve("fehlt.txt").toString())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fetch-blocklist.sh");
    }

    private static AppProperties props(String blocklistFile) {
        return new AppProperties("https://x", "k", "p", "o", "i", null, null, blocklistFile, "no-reply@x", null, null, null, null, null);
    }
}
