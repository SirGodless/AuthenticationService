# Protokoll (Audit-Log)

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Nur anhängen, in der Datenbank erzwungen:** Ein Trigger verbietet `DELETE` und jede Änderung außer dem Leeren der IP. Das Leeren nach 90 Tagen erledigt ein stündlicher Aufräum-Job (außerdem: abgelaufene Sitzungen, alte Zähler, alte Mail-Tokens nach 7 Tagen). Für eine spätere manuelle Anonymisierung muss Joshua den Trigger bewusst deaktivieren.
- Protokolliert werden Logins (Erfolg, Fehlschlag, gesperrt, abgelehnt) mit IP, Abmelden, Passwort geändert oder zurückgesetzt, Reset angefordert, E-Mail bestätigt, Limits. **Nie** Passwörter, Tokens, Codes und auch nicht die eingegebene Kennung (kann aus Versehen ein Passwort sein). Fehlereinträge werden in einer eigenen Transaktion geschrieben, damit sie auch bei abgebrochenen Anfragen bleiben.
