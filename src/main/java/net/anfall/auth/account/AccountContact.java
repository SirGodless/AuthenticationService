package net.anfall.auth.account;

import java.util.UUID;

/** Kontaktdaten für Mails an ein Konto (Bestätigung, Passwort-Reset). */
public record AccountContact(UUID id, String username, String email, String language, boolean emailVerified,
                             boolean banned) {
}
