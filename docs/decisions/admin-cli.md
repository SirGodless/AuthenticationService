# Betreiber-Befehle

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- `auth-admin reset-2fa <username>` im Backend-Container (siehe README). Startet das Backend ohne Webserver, entfernt 2FA und Wiederherstellungscodes, beendet alle Sitzungen, protokolliert mit `ip=cli`.
- **Lücke bis Etappe 6:** Ein Konto ohne 2FA kann sie erst über die Profilseite wieder einrichten. Die API dafür (und das Abschalten durch normale User) gibt es noch nicht. Siehe offene Fragen.
