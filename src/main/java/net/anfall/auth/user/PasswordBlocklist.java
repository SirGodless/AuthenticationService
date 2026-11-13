package net.anfall.auth.user;

/**
 * Sperrliste bekannter oder geleakter Passwörter. Verglichen wird das ganze Passwort, nie Teile davon.
 * Austauschbar: Später kann eine Umsetzung mit dem Pwned-Passwords-Index (offline) dazukommen,
 * ohne dass der Rest etwas merkt. Kein Fremddienst, keine Online-Abfrage.
 */
public interface PasswordBlocklist {

    boolean isBlocked(String password);
}
