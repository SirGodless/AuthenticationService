# Admin-Dashboard und Admin-Schnittstelle (Etappe 4)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

## Zugriff: angemeldet, Recht, 2FA

Jede Admin-Funktion läuft durch `security/AdminAccessGuard`. Die Reihenfolge ist fest:

1. Nicht angemeldet: 401 (die Seite leitet zum Login um und kommt danach zurück).
2. Recht fehlt: 403 `forbidden`.
3. Recht da, aber keine 2FA eingerichtet: 403 `two_factor_required` (**[E]** Pflicht-2FA vor jeder Admin-Funktion, Abschnitt 3). Die Seite zeigt dann den Hinweis mit Link zur 2FA-Seite.

Die Rechteentscheidung liegt ausschließlich im Backend. Die Oberfläche blendet nur Reiter aus, die das Backend meldet, und zeigt Fehlertexte an.

**[A]** `GET /api/admin/overview` ist die einzige Ausnahme von der 2FA-Pflicht: Sie sagt nur, welche Reiter es gibt, ob der User Verwaltungsrechte hat und ob seine 2FA eingerichtet ist. Ohne diese Ausnahme könnte die Seite einem Admin ohne 2FA nicht erklären, was fehlt.

## Schnittstellen (`/api/admin/...`)

| Pfad | Zweck |
| :- | :- |
| `GET users?q=`, `GET users/{id}` | Liste mit Suche (Username oder E-Mail, höchstens 200), Detail mit Rollen, direkten und wirksamen Rechten |
| `POST users/{id}/ban`, `.../unban` | Bann (beendet Sitzungen und Tokens), Bann aufheben |
| `POST users/{id}/reset-2fa` | 2FA und Wiederherstellungscodes entfernen, Sitzungen und Tokens beenden |
| `POST users/{id}/force-password-reset` | siehe unten |
| `PUT/DELETE users/{id}/roles/{roleId}` | Rolle zuweisen oder entziehen |
| `PUT/DELETE users/{id}/permissions/{key}` | direktes Recht setzen (Body `{number}`, `{unlimited:true}` oder leer) oder entfernen |
| `GET/POST/PATCH/DELETE roles...`, `PUT/DELETE roles/{id}/permissions/{key}` | Rollen und ihre Rechte |
| `GET/POST/PATCH/DELETE permissions...` | Rechte verwalten |
| `GET audit?action=&user=&before=&limit=` | Protokoll, neueste zuerst, seitenweise (`before` = ID des letzten Eintrags), höchstens 100 pro Seite |

Alle schreibenden Aufrufe landen im Protokoll (Abschnitt 9), ohne Geheimnisse.

## Passwort-Reset erzwingen (NIST, von Joshua bestätigt)

`useradmin/ForcedPasswordResetService`: Das Passwort des Kontos wird auf einen unbenutzbaren Wert gesetzt (Anmeldung mit dem alten Passwort schlägt fehl), alle Sitzungen und Refresh-Tokens enden, und das Konto bekommt eine Mail mit einem Reset-Link (gleicher Weg wie "Passwort vergessen", 1 Stunde gültig, einmalig). **[A]** Das geht nur bei bestätigter E-Mail-Adresse (`email_not_verified`), sonst gäbe es keinen Weg zurück ins Konto. Die 2FA bleibt unberührt.

## Oberfläche (`UserHub`)

- Seite `/admin` (nginx liefert `pages/admin.html`), Skript `pages/admin.js` mit Reitern. **[A]** Die Seiten laufen gleich-origin unter `auth.anfall.net` (siehe `hub-ui-authentication.md`, Variante 1); CORS für `anfall.net` kommt erst mit dem Hub in Etappe 6.
- Je Reiter ein Modul in `assets/js/admin/`: `users-list.js`, `users-detail.js`, `roles.js`, `permissions.js`, `audit.js`. Gemeinsam: `ui.js` (Aufrufe mit Umleitung bei 401, Meldungen, Zeitformat Europe/Berlin), `permission-value.js` (Eingabe und Anzeige von Rechte-Werten), `shared/dom.js` (Elemente bauen ohne `innerHTML`, Texte aus Daten landen nie als HTML im Dokument).
- Die Seite zeichnet sich bei Sprachwechsel neu. Eine Ansicht, die erst nach einem Reiterwechsel fertig wird, zeichnet nichts mehr (Zähler `state.view`), sonst überschriebe sie die neue Ansicht.
- **[A]** Rückfragen und Texteingaben (Bann bestätigen, Rolle umbenennen) laufen vorerst über die Standarddialoge des Browsers. Das Design der Oberfläche kommt mit Etappe 6.
- Sprachdateien nach Thema: `admin`, `admin-users`, `admin-roles`, `admin-permissions`, `admin-audit`, plus `twofactor` für die 2FA-Seite.

## Bewusst noch nicht im Dashboard

Dienste und Clients (bis dahin `auth-admin register-service` und `grant-service`), alle Einladungslinks und die Einstellungen der Link-Grenzen (Etappe 5), Statusanzeige der Dienste (Etappe 6).
