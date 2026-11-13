# Datenmodell

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Personenbezogene Daten getrennt:** `users` (UUID, Username, Sprache, Bann, `invited_by`), `user_pii` (E-Mail, bestätigt), `user_credentials` (Passwort-Hash), `user_totp`, `recovery_codes`. Eine spätere Anonymisierung muss so nur `user_pii` und die Zugangsdaten entfernen. Die Funktion selbst wird nicht gebaut (Abschnitt 7).
- **[A] Rechte und Rollen:** Tabellen `permissions` (Typ, Richtung, "unbegrenzt erlaubt", "per Link vergebbar"), `roles`, `user_roles`. Die eingebaute Rolle `Admin` hat das Flag `all_admin_permissions`: Sie enthält implizit jedes Recht mit Präfix `admin.`, auch später neue, ohne dass Zeilen nachgepflegt werden müssen. Die Startliste der Rechte aus Abschnitt 5 ist per Migration angelegt. `role_permissions` und direkte Rechte pro User kommen mit Etappe 4.
- **[A] Protokoll:** Tabelle `audit_log` ist da, das Setup schreibt hinein. Das Leeren der IP nach 90 Tagen und der Schutz gegen Änderungen (nur anhängen) folgen in Etappe 2.
