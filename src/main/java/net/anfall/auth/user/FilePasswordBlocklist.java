package net.anfall.auth.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.anfall.auth.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Liest eine Textdatei mit einem Passwort pro Zeile in den Speicher (rund 100.000 Zeilen, wenige MB).
 * Startet den Dienst nicht, wenn die Datei fehlt: Lieber ein klarer Fehler als stillschweigend ohne Sperrliste.
 */
@Component
public class FilePasswordBlocklist implements PasswordBlocklist {

    private static final Logger log = LoggerFactory.getLogger(FilePasswordBlocklist.class);
    private final Set<String> blocked = new HashSet<>();

    public FilePasswordBlocklist(AppProperties props) {
        Path file = Path.of(props.passwordBlocklistFile());
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("Passwort-Sperrliste nicht gefunden: " + file
                    + ". Sie wird beim Docker-Build geladen, zum Entwickeln: scripts/fetch-blocklist.sh <Zielordner>");
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.map(String::strip).filter(l -> !l.isEmpty()).forEach(blocked::add);
        } catch (IOException e) {
            throw new IllegalStateException("Passwort-Sperrliste nicht lesbar: " + file, e);
        }
        log.info("Passwort-Sperrliste geladen: {} Einträge", blocked.size());
    }

    @Override
    public boolean isBlocked(String password) {
        return blocked.contains(password);
    }
}
