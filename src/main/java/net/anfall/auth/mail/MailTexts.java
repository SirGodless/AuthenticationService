package net.anfall.auth.mail;

import java.util.Locale;
import java.util.ResourceBundle;
import org.springframework.stereotype.Component;

/**
 * Holt Betreff und Text einer Mail aus den Sprachdateien unter src/main/resources/mail/
 * (eine Datei pro Mail und Sprache, z. B. confirm_email.properties für Englisch und confirm_email_de.properties
 * für Deutsch). Die Platzhalter %1$s (Username) und %2$s (Link) füllt render().
 */
@Component
class MailTexts {

    // Ohne Rückfall auf die Systemsprache: Englisch ist der Standard, nicht die Sprache des Servers
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    record Text(String subject, String body) {
    }

    Text render(String mailName, String language, String username, String link) {
        ResourceBundle texts = ResourceBundle.getBundle("mail." + mailName, Locale.forLanguageTag(language),
                NO_FALLBACK);
        return new Text(texts.getString("subject"), texts.getString("body").formatted(username, link));
    }
}
