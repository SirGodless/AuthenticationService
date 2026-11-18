# Einladungslinks und Registrierung (Etappe 5)

Kennzeichnung: **[E]** von Joshua entschieden (CLAUDE.md Abschnitt 4). **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

Code: Paket `invite` (Backend), Seiten `/invite/<token>` und `/account/links` sowie die Dashboard-Reiter "Links" und "Settings" (UserHub). Einstellungen liegen im Paket `settings`.

## Ablauf

1. Ein User mit `links.create` erzeugt einen Link (`POST /api/invites`). Die Antwort enthält die Adresse `https://auth.anfall.net/invite/<token>` **einmal**. **[A]** Gespeichert ist nur der Hash (SHA-256 eines 256-Bit-Zufallstokens), der Token steht nie im Protokoll und lässt sich später nicht mehr anzeigen.
2. Die Registrierungsseite prüft den Link nur (`GET /api/invite/<token>`, verbraucht nichts: Mail- und Messenger-Vorschauen öffnen Links von selbst).
3. Beim **Absenden** des Formulars (`POST /api/invite/<token>/register`) wird der Link verbraucht. Das Konto entsteht mit unbestätigter E-Mail, `invited_by` zeigt auf den Ersteller, die Bestätigungsmail geht in der Sprache, die auf der Seite gewählt war. Ohne Bestätigung kein Login (wie bei allen Konten).
4. Fehler im Formular (Username oder E-Mail vergeben, Passwortregeln) lassen den Link **unverbraucht** (die Transaktion wird zurückgerollt).
5. Ungültige, abgelaufene, benutzte und deaktivierte Links geben dieselbe neutrale Antwort (404 `invalid_link`, gleicher Text).

## Atomares Einlösen (**[E]**)

Die Zeile des Links wird beim Einlösen mit `SELECT ... FOR UPDATE` gesperrt. Zwei gleichzeitige Absendungen laufen so nacheinander, die zweite sieht den Link als benutzt. Getestet mit 2 und mit 8 parallelen Anfragen: immer genau ein Konto, ein verbrauchter Link, eine Mail. Gleichzeitige Treffer auf denselben Username oder dieselbe E-Mail über zwei verschiedene Links fängt die Eindeutigkeit der Datenbank ab und wird zum üblichen Formularfehler.

## Gültigkeit (**[E]** Werte, **[A]** Auslegung)

- Standard 7 Tage, Minimum 1 Stunde, Maximum 30 Tage. Mit Rolle oder Rechten: höchstens 3 Tage, und 3 Tage sind dann auch der Standard.
- Gespeichert ist ein absolutes `expires_at`. Die Grenzen prüft nur das Backend (`validity_too_short`, `validity_too_long`, `validity_too_long_privileged`). Die Dauer wird in Stunden angegeben.
- Ein Link ist nicht verlängerbar, nur deaktivierbar.
- **Im Dashboard einstellbar** (Reiter Settings, Recht `admin.settings.manage`): Minimum, Standard, Maximum und das kürzere Maximum für Links mit Rechten, alles in der Tabelle `settings`. **[A]** Die 30 Tage sind eine feste Obergrenze, man kann das Maximum nur senken. Standard und das kürzere Maximum dürfen das Maximum nicht überschreiten, und beide müssen mindestens so lang wie das Minimum sein (`invite_limits_invalid`).

## Was ein Link vergeben darf (**[E]**)

`InviteGrantRules` prüft beim Erstellen **und** beim Einlösen dasselbe:

- Der Ersteller darf nur Rechte vergeben, die er selbst hat. Bei Zahlen höchstens seinen Wert (bei "niedriger = mehr" mindestens), "unbegrenzt" nur, wenn er selbst unbegrenzt hat.
- Das Recht muss "per Link vergebbar" sein. **[A]** `admin.*`-Rechte und die Rolle Admin werden **nie** per Link vergeben, auch wenn jemand sie als vergebbar markiert.
- Eine Rolle ist nur erlaubt, wenn der Ersteller alle ihre Rechte hält und alle link-vergebbar sind. Das zählt als Link mit Rechten (3 Tage).
- Gespeichert werden Rolle (Name und ID), die aufgelösten Rechte der Rolle und die direkt gewählten Rechte zum Zeitpunkt der Erstellung sowie, was beim Einlösen vergeben wurde. `role_id` hat bewusst keinen Fremdschlüssel, damit eine Rolle trotz bestehender Links gelöscht werden kann.

## Ersteller beim Einlösen neu prüfen (**[E]**)

Beim Einlösen und beim Öffnen gilt der Link nur, wenn der Ersteller nicht gebannt ist **und** noch alle Rechte hat, auch den **aktuellen** Inhalt der Rolle. Sonst wird der ganze Link abgelehnt, mit der neutralen Antwort. **[A]** Der Link wird dabei nicht verändert: Bekommt der Ersteller das Recht zurück, gilt er wieder. Eine gelöschte Rolle macht den Link ungültig.

## Platzlimit (**[E]**)

`links.max_active` (Zahl, höher = mehr, "unbegrenzt" möglich) begrenzt die gleichzeitig **aktiven** Links: nicht benutzt, nicht deaktiviert, nicht abgelaufen. Der Platz wird frei, wenn ein Link benutzt wird, abläuft oder deaktiviert wird. **[A]** "Nicht gesetzt" heißt kein Platz (`link_limit_reached`), passend zu "keine Quelle setzt es, dann hat der User es nicht". Beim Erstellen wird die Zeile des Users gesperrt, damit zwei gleichzeitige Anfragen den letzten Platz nicht beide bekommen (getestet).

## Nie löschen, nur deaktivieren (**[E]**)

Es gibt keinen Löschweg. Ein Link hat den Status `active`, `used` oder `deactivated` mit Grund (`manual`, `admin`, `creator_banned`). "Abgelaufen" wird aus `expires_at` berechnet. **[A]** Admins mit `admin.links.view_all` sehen alle Links und dürfen jeden deaktivieren (ein eigenes Recht dafür gibt es in der Startliste nicht). Das Banden eines Erstellers deaktiviert seine offenen Links (`AccountBanService`). Konten, die vorher über seine Links entstanden sind, bleiben bestehen und werden nicht gebannt. Entbannen macht deaktivierte Links nicht wieder aktiv.

## Sperre bei falschen Links (**[E]**, Umsetzung **[A]**)

Wer mehr als 3-mal pro Stunde einen Link öffnet oder absendet, den es **nie gab**, wird einen Tag für den Link-Endpunkt gesperrt (der vierte falsche Link löst aus, danach 429 `ip_blocked`, auch für gültige Links). Abgelaufene, benutzte und deaktivierte Links zählen nicht. Die Sperre gilt pro IP (IPv6 pro /64, echte IP nur über Caddy, siehe `rate-limits.md`) und nicht für den Login. Eigene Tabelle `invite_ip_blocks` statt des allgemeinen Zählers, damit der Admin die gesperrten Adressen sehen und aufheben kann (Reiter Settings, `admin.settings.manage`). Alte Zähler löscht ein stündlicher Job nach 2 Tagen.

## Oberfläche

- `/invite/<token>`: Registrierungsformular wie im Setup (Username, E-Mail, Passwort mit "anzeigen", Sprache der Seite wird die Konto-Sprache), danach der Hinweis auf die Bestätigungsmail. Eigene Zustände für ungültig und gesperrt.
- **[A]** `/account/links`: eigene Links erstellen (Dauer, Rolle, Rechte mit Zahlenwert), den neuen Link einmal sehen und kopieren, Liste mit Status und Deaktivieren. Das ist ein Vorgriff auf die Profilseite aus Etappe 6, die diese Funktion später aufnimmt. Verlinkt von der Startseite und vom Dashboard.
- Dashboard: Reiter "Links" (alle Links) und "Settings" (Grenzen, gesperrte Adressen).

Tests: `InviteLinkCreationTest`, `InviteRegistrationTest`, `InviteIpBlockTest`, `InviteLimitsAdminTest` und der Browser-Test.
