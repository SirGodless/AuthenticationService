# Rechte und Rollen (Etappe 4)

Kennzeichnung: **[E]** von Joshua entschieden (CLAUDE.md Abschnitt 5). **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

## Wie ein Recht berechnet wird

Die wirksamen Rechte eines Users werden **bei jeder Abfrage neu aus allen Quellen berechnet** (alle Rollen und die direkten Rechte), nie per Delta (**[E]**). Rollen sind Live-Gruppen: Ändert man eine Rolle, gilt das sofort für alle Mitglieder, weil nichts zwischengespeichert ist.

Die Rechenregeln stehen in einer Klasse ohne Datenbank (`permission/EffectivePermissionCalculator`), damit sich Konfliktfälle ohne Aufbau testen lassen:

- **Boolesch:** Hat, wer das Recht aus mindestens einer Quelle bekommt.
- **Zahl, Richtung "höher = mehr":** der größte Wert. **"Niedriger = mehr":** der kleinste Wert.
- **"Unbegrenzt"** schlägt jede Zahl, gibt es aber nur bei "höher = mehr" (**[E]**). Bei "niedriger = mehr" oder wenn das Recht es nicht erlaubt, wird es ignoriert.
- **"Nicht gesetzt"** ist ein eigener Zustand und nie 0 (**[E]**). Eine Quelle, die den Wert nicht setzt, beeinflusst den Vergleich nicht. Setzt keine Quelle ihn, hat der User das Recht nicht. Eine echte 0 ist ein gültiger Wert und bei "niedriger = mehr" sogar der stärkste.
- Die Rolle `Admin` enthält jedes **definierte boolesche** `admin.*`-Recht von selbst (auch später neu angelegte), ohne dass Zeilen nachgepflegt werden. Sie gibt keinen Zugang zu Diensten (`service.*`).

`permission/PermissionService.effective(userId)` sammelt die Quellen und ruft den Rechner auf. `has`, `value` und `hasAnyAdminPermission` bauen darauf auf.

Tests: `EffectivePermissionCalculatorTest` (10 Konfliktfälle, ohne Datenbank), `PermissionServiceTest` (mit Datenbank: mehrere Rollen, direkte Rechte, Rolle Admin), `RoleAdminApiTest` (Live-Verhalten über die API, Neuberechnung beim Entfernen einer Rolle).

## Datenmodell (Migrationen V14 und V16)

- **V14:** `role_permissions` und `user_permissions` bekommen `number_value` (bigint) und `unlimited`. Eine Prüfregel der Datenbank verbietet "Zahl und unbegrenzt zugleich". Ein Eintrag ohne beides heißt bei booleschen Rechten "vergeben".
- **[A] Zahlenwerte sind 0 oder größer.** Negative Werte sind nicht erlaubt (`number_negative`), die Spezifikation nennt keine.
- **V16:** Neues eingebautes Recht `admin.users.force_password_reset`, weil für "Passwort-Reset erzwingen" in der Startliste kein Recht passt. **[A]** Die Rolle `Admin` enthält es automatisch.

## Eigene Rechte anlegen

- **[A] Schlüssel** wie `media.upload`: Kleinbuchstaben, Ziffern und Unterstriche, mindestens zwei Teile mit Punkt getrennt, höchstens 64 Zeichen. Die Präfixe `admin.` und `service.` sind **reserviert**: `admin.*` würde automatisch in die Rolle Admin fallen und Verwaltungsmacht geben, `service.*` entsteht nur mit einem Dienst.
- Typ `boolean` oder `number`. Zahlenrechte brauchen eine Richtung, boolesche haben keine. "Unbegrenzt erlauben" nur bei "höher = mehr".
- **[A] Löschen** geht nur für eigene Rechte (nicht eingebaut, nicht `service.*`) und nur, wenn kein User, keine Rolle und kein Dienst sie mehr benutzt (sonst 409 `permission_in_use`).
- Nachträglich änderbar sind nur Beschreibung und "per Link vergebbar" (Typ und Richtung zu ändern würde bestehende Werte entwerten).
- **Wirkung ist sichtbar gemacht (Abschnitt 5):** Das Dashboard zeigt oben den Hinweis, dass ein Recht nur wirkt, wenn etwas es durchsetzt, nämlich als Anmeldevoraussetzung eines Dienstes. Die Tabelle hat die Spalten "Durchgesetzt von" (Dienste, die es verlangen, sonst "noch nichts (keine Wirkung)") und "Vergeben" (Zahl der Zuweisungen).

## Rollen

- Anlegen, umbenennen, löschen (Namen eindeutig, 2 bis 40 Zeichen). **[A]** Eingebaute Rollen (`Admin`) lassen sich nicht umbenennen oder löschen.
- **[A]** In der Rolle `Admin` gibt es keine Einträge für `admin.*`-Rechte (sie enthält sie ohnehin). Andere Rechte (z. B. Zahlenrechte) lassen sich eintragen.
- Dem **letzten Admin** lässt sich die Rolle `Admin` nicht entziehen (`cannot_remove_last_admin`), gleiche Regel wie beim Bann (`account/LastAdminRule`).
- Rechtewert prüft `PermissionService.validate` (Typ, Richtung, "unbegrenzt", Zahl nicht negativ).

## Wer darf was

| Funktion | Recht |
| :- | :- |
| Benutzerliste und Details sehen | `admin.users.view` |
| Bannen, Bann aufheben | `admin.users.ban` |
| 2FA eines Users zurücksetzen | `admin.users.reset_2fa` |
| Passwort-Reset erzwingen | `admin.users.force_password_reset` |
| Rollen anlegen, ändern, zuweisen | `admin.roles.manage` |
| Rechte anlegen, ändern, löschen, direkt an User vergeben | `admin.permissions.manage` |
| Protokoll lesen | `admin.audit.view` |

**[A]** Die Listen der Rollen und Rechte dürfen auch lesen, wer sie für seine Seiten braucht (Rollenliste mit `admin.users.view`, Rechteliste mit einem der drei Rechte `admin.permissions.manage`, `admin.roles.manage`, `admin.users.view`), damit Dialoge Auswahllisten füllen können. Geändert wird nur mit dem jeweiligen Recht.

**Bekanntes Risiko aus der Spezifikation:** Wer `admin.roles.manage` oder `admin.permissions.manage` hat, kann sich selbst jedes weitere Recht geben. Das ist der Grund für die Pflicht-2FA bei allen `admin.*`-Rechten, sie ist hier umgesetzt (siehe `admin-dashboard.md`).
