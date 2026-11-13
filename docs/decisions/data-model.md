# Datenmodell

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Personenbezogene Daten getrennt:** `users` (UUID, Username, Sprache, Bann, `invited_by`), `user_pii` (E-Mail, bestätigt), `user_credentials` (Passwort-Hash), `user_totp`, `recovery_codes`. Eine spätere Anonymisierung muss so nur `user_pii` und die Zugangsdaten entfernen. Die Funktion selbst wird nicht gebaut (Abschnitt 7).
- **[A] Rechte und Rollen:** Tabellen `permissions` (Typ, Richtung, "unbegrenzt erlaubt", "per Link vergebbar"), `roles`, `user_roles`. Die eingebaute Rolle `Admin` hat das Flag `all_admin_permissions`: Sie enthält implizit jedes Recht mit Präfix `admin.`, auch später neue, ohne dass Zeilen nachgepflegt werden müssen. Die Startliste der Rechte aus Abschnitt 5 ist per Migration angelegt. `role_permissions` und direkte Rechte pro User kommen mit Etappe 4.
- **[A] Protokoll:** Tabelle `audit_log` ist da, das Setup schreibt hinein. Das Leeren der IP nach 90 Tagen und der Schutz gegen Änderungen (nur anhängen) folgen in Etappe 2.

## Hinzugekommen mit Etappe 3 (OIDC)

- **`oauth2_registered_client`, `oauth2_authorization`** (V10): Tabellen von Spring Authorization Server für Clients und Autorisierungen mit Tokens, nach deren mitgelieferten Schema-Dateien für PostgreSQL angepasst (`blob` wird `text`, `timestamp` wird `timestamptz`). `principal_name` ist die User-UUID und hat einen Index, damit sich alle Tokens eines Users schnell widerrufen lassen. Die Zustimmungstabelle fehlt bewusst.
- **`oidc_signing_keys`** (V11): Signierschlüssel. Höchstens ein aktiver (Teilindex), private Teile verschlüsselt und nur beim aktiven Schlüssel vorhanden.
- **`services`** (V12): Dienste mit Anzeigename, `open_mode` (`new_tab` oder `embedded`, **[E]** Abschnitt 6, zuerst nur `new_tab`), Health-URL und dem Recht, das den Zugang regelt. Verknüpft mit dem Client über `client_id`.
- **`role_permissions`, `user_permissions`** (V13): Welche Rechte eine Rolle enthält und welche ein User direkt bekommt. Vorerst nur boolesche Rechte, Etappe 4 ergänzt Zahlenrechte in einer neuen Migration.
