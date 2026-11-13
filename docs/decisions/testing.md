# Tests und Prüfungen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- 115 automatische Tests (Stand nach Pepper und Umschnitt der Migrationen), darunter Rennen beim gleichzeitigen Einlösen eines Reset-Links, alle Limits, Sitzungslaufzeiten, Cookie-Eigenschaften, 2FA-Wiederverwendung, Neutralität der Antworten, Protokollschutz, Unicode. Der Mailversand läuft im Test über einen echten SMTP-Server (GreenMail).
- Ende-zu-Ende im Browser (`tests/e2e/run.sh`, mit `tests/check-i18n.js` im UserHub-Repo für die Sprachdateien) mit Test-Caddy über HTTPS: Setup, Login mit 2FA, falscher Code, "Passwort vergessen" mit Mail aus Mailpit, Sperrlisten-Fehler, Reset, Wiederherstellungscode, Abmelden, Cookie-Flags im echten Browser.
- Lizenzen: siehe `docs/THIRD-PARTY.md`.

