package net.anfall.auth.mail;

import net.anfall.auth.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Verschickt Mails an den eigenen Mailserver. Texte stehen in den Sprachdateien unter resources/mail/ (siehe MailTexts), nichts steht fest im Code. Mails sind reiner Text.
 *
 * Versand läuft im Hintergrund und erst nach dem Commit der Datenbank: Die Antwort an den Besucher dauert
 * gleich lang, egal ob es den Account gibt (wichtig bei "Passwort vergessen"), und ein Link in einer Mail
 * zeigt nie auf einen Token, der durch einen Rollback gar nicht existiert.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private final JavaMailSender sender;
    private final MailTexts texts;
    private final AppProperties props;
    private final MailService self;

    public MailService(JavaMailSender sender, MailTexts texts, AppProperties props,
                       @org.springframework.context.annotation.Lazy MailService self) {
        this.sender = sender;
        this.texts = texts;
        this.props = props;
        this.self = self;
    }

    /** Bestätigungslink für die E-Mail-Adresse. */
    public void sendConfirmEmail(String to, String language, String username, String token) {
        sendLater(to, language, "confirm_email", username, props.publicBaseUrl() + "/confirm/" + token);
    }

    /** Link zum Zurücksetzen des Passworts. */
    public void sendPasswordReset(String to, String language, String username, String token) {
        sendLater(to, language, "password_reset", username, props.publicBaseUrl() + "/reset/" + token);
    }

    private void sendLater(String to, String language, String key, String username, String link) {
        Runnable job = () -> self.deliver(to, language, key, username, link);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    /** Läuft im Hintergrund (@Async, abschaltbar mit app.mail.async=false für Tests). */
    @Async
    public void deliver(String to, String language, String key, String username, String link) {
        MailTexts.Text text = texts.render(key, language, username, link);
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(props.mailFrom());
        mail.setTo(to);
        mail.setSubject(text.subject());
        mail.setText(text.body());
        try {
            sender.send(mail);
        } catch (MailException e) {
            // Weder Adresse noch Link ins Log: Beides ist privat bzw. geheim.
            log.error("Mail '{}' konnte nicht gesendet werden: {}", key, e.getClass().getSimpleName());
        }
    }
}
