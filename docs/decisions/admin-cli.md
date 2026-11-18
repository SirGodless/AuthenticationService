# Betreiber-Befehle

**Stand Etappe 3:** Jeder Befehl ist eine eigene Klasse im Paket `admincli` (Name endet auf `Command`), der Verteiler `AdminCli` sucht sie nach Namen. Aufruf: `docker compose exec auth-backend auth-admin <befehl> <parameter>`. Befehle: `reset-2fa <username>`, `register-service <key> <Anzeigename> <redirect-uri>[,...]`, `grant-service <username> <key>`, `rotate-signing-key`. `register-service` und `grant-service` sind für den Anfang gedacht, bis das Dashboard (Etappe 4) die Verwaltung übernimmt. `reset-2fa` widerruft jetzt auch alle Tokens. Der Start ohne Webserver wird im echten Container im Browser-Test geprüft.

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- `auth-admin reset-2fa <username>` im Backend-Container (siehe README). Startet das Backend ohne Webserver, entfernt 2FA und Wiederherstellungscodes, beendet alle Sitzungen, protokolliert mit `ip=cli`.
- **Lücke bis Etappe 6:** Ein Konto ohne 2FA kann sie erst über die Profilseite wieder einrichten. Die API dafür (und das Abschalten durch normale User) gibt es noch nicht. Siehe offene Fragen.
