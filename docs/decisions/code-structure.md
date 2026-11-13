# Code-Struktur und Lesbarkeit

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme.

## Regeln (Joshua, 13.11.2026, gelten für alle weiteren Etappen)

1. Dateien nach Aufgabe aufteilen, keine riesigen Sammeldateien. Ordner richten sich nach Fachthema (Konten, Einladungen, Berechtigungen, OIDC, Protokoll), wo das lesbarer ist.
2. Namen so wählen, dass man Funktionen über Ordner- und Dateinamen findet.
3. Jedes Repo hat eine `README.md` mit Ordnerübersicht (ein Satz pro Hauptordner), Startanleitung und Testbefehlen und hält sie bei jeder Etappe aktuell.
4. Die gewählte Struktur wird hier begründet. Spätere Umbauten brauchen ebenfalls eine Begründung.
5. Gilt auch für Tests, Migrationen, Sprachdateien, Docker-Dateien und Doku.

## Backend (`AuthenticationService`)

**Ein Paket pro Fachthema, darin die Dateien nach Aufgabe.** Wer wissen will, wie der Passwort-Reset funktioniert, öffnet `password/` und findet `PasswordResetService` (die Abläufe) und `PasswordResetController` (die Schnittstelle) nebeneinander. Vorher lag alles unter `auth/`, `user/` und `config/` nach technischer Art (alle Services zusammen, alle Controller zusammen), man musste dieselbe Funktion an fünf Stellen suchen.

| Paket | Fachthema |
| :- | :- |
| `account` | Konten: anlegen, Eingaben prüfen, Zugriff auf die Kontotabellen |
| `login` | Anmelden (Passwort, zweiter Schritt), Abmelden, "Wer bin ich" |
| `login/session` | Anmeldesitzungen und ihre Anbindung an Spring Security |
| `oidc` | OIDC-Provider: Filterkette, Anmeldung für den Provider, Rechteprüfung, Token-Anpassung, Signierschlüssel, Widerruf |
| `service` | Dienste (Vaultwarden, ...): anlegen, Recht, OIDC-Client |
| `permission` | Berechtigungen eines Users (wird mit Etappe 4 ausgebaut) |
| `password` | Passwort-Regeln und Hashing, Passwort vergessen, Passwort ändern |
| `password/check` | Die einzelnen Prüfungen (Länge, Sperrliste, Kontextwörter) |
| `emailconfirmation` | E-Mail-Adresse bestätigen, Bestätigungsmail erneut senden |
| `emailtoken` | Einmal-Links per Mail (gemeinsam von Bestätigung und Reset genutzt) |
| `twofactor` | TOTP und Wiederherstellungscodes |
| `setup` | Setup-Link und erster Admin |
| `admincli` | Befehle für den Betreiber im Container, eine Klasse pro Befehl |
| `audit` | Protokoll |
| `ratelimit` | Zähler und Sperren |
| `mail` | Mailversand und Mail-Texte |
| `security` | Zugriffsregeln und CSRF-Schutz |
| `crypto` | Zufall, Hashes, Verschlüsselung (fachthemenübergreifend) |
| `config` | Einstellungen und Startprüfungen |
| `web` | Gemeinsame Teile der API (Fehler, Antworten, Client-IP) |

Spätere Etappen bekommen ihre eigenen Pakete: `invite` (Einladungslinks). `permission` wird mit Etappe 4 zu Rechten und Rollen ausgebaut.

**Aufteilung großer Klassen.** Vorher waren `AuthController` (8 Schnittstellen), `LoginService` (Passwort, 2FA, TOTP-Prüfung, Abmelden), `SetupService` (Link und Admin) und `MaintenanceJobs` (4 Aufräumarbeiten) Sammelklassen. Jetzt hat jede Klasse eine Aufgabe, im Namen erkennbar:

- Schnittstellen: ein Controller je Seite der UI (`LoginController`, `PasswordResetController`, `PasswordChangeController`, `EmailConfirmationController`, `CurrentUserController`). Die Adressen der API sind unverändert.
- Logik: `LoginService` (erster Schritt), `SecondFactorLoginService` (zweiter Schritt), `SecondFactorChecker` (Codes prüfen), `SetupTokenService` (Link), `FirstAdminService` (Admin anlegen).
- SQL für Konten steht gesammelt in `AccountRepository`. Vorher hatten drei Klassen dieselben Abfragen.
- Betreiber-Befehle: je ein Befehl eine Klasse (`Reset2faCommand`, `RegisterServiceCommand`, ...), der Verteiler `AdminCli` kennt sie nur über die Schnittstelle `AdminCommand`.
- Provider: `OidcBeansConfig` (Bausteine, auch ohne Webserver), `OidcAuthorizationServerConfig` (Filterkette), `ServicePermissionFilter` (Zugang), `OidcTokenCustomizer` (Prüfung und Claims bei jedem Token), `SigningKeyService` (Schlüssel), `TokenRevocationService` (Widerruf).
- Aufräum-Jobs: je eine kleine Klasse beim zugehörigen Fachthema, Name endet auf `Job` (`AuditIpRetentionJob`, `ExpiredSessionsJob`, `ThrottleCleanupJob`, `EmailTokenCleanupJob`).
- Mail-Texte liegen in einer Datei pro Mail und Sprache (`resources/mail/`), die Textsuche in `MailTexts`.

**Tests spiegeln die Pakete.** Zu jedem Paket gibt es einen Testordner gleichen Namens, und jede Testklasse prüft ein Thema (`LoginLimitsTest`, `SecondFactorLoginTest`, `SessionCookieTest`, ...). Gemeinsame Hilfen (Testdatenbank, Test-Mailserver, Browser-Simulation) stehen in `testsupport/`.

**Migrationen.** Eine Migration pro Fachthema und Änderung, mit sprechendem Namen: `V1__accounts`, `V2__two_factor`, `V3__permissions_and_roles`, `V4__setup_tokens`, `V5__audit_log`, `V6__login_sessions`, `V7__email_tokens`, `V8__rate_limit_throttle`, `V9__audit_log_append_only`. **[E]** Joshua hat bestätigt (13.11.2026), dass noch keine Datenbank existiert. Deshalb habe ich die ersten beiden Sammel-Migrationen vor dem ersten Deployment nach Themen umgeschnitten. Geprüft: Das Schema und die Daten beider Fassungen sind identisch (Vergleich per `pg_dump`). **Ab dem ersten Deployment ist jede Migration unveränderlich**, weil Flyway die Prüfsumme prüft und bei einer Änderung den Start verweigert. Neue Änderungen kommen immer als neue Datei (z. B. `V10__invite_links.sql`).

**Docker-Dateien.** `Dockerfile` und `docker-compose.yml` liegen im Hauptordner, weil das die Konvention ist und `docker compose up` sie dort sucht. Hilfsdateien dazu stehen thematisch sortiert: `scripts/` (Skripte, die in den Image oder auf dem Server laufen), `tests/e2e/` (Browser-Test samt Test-Caddy).

**Doku.** `README.md` (Überblick, Start, Tests, Mailserver-Einrichtung), `docs/DEPLOY.md` (Deployment), `docs/OPEN-QUESTIONS.md`, `docs/NIST-800-63B-4.md`, `docs/THIRD-PARTY.md`, und die Entscheidungen nach Thema in `docs/decisions/` (diese Datei gehört dazu). `docs/DECISIONS.md` ist nur noch das Inhaltsverzeichnis.

## UI (`UserHub`)

**Ordner nach Aufgabe, Dateien nach Seite.**

- `public/pages/`: eine HTML-Datei pro Seite. Der Dateiname entspricht der Adresse (`login.html` für `/login`).
- `public/assets/js/pages/`: ein Skript pro Seite, gleicher Name wie die Seite. Es enthält nur, was diese Seite tut.
- `public/assets/js/shared/`: was mehrere Seiten brauchen (`api.js` für Aufrufe an das Backend, `i18n.js` für Übersetzungen, `password-toggle.js` für "Passwort anzeigen").
- `public/assets/css/`: `tokens.css` (Farben, der Teil, den ein Light Mode ersetzt), `layout.css`, `forms.css`, `setup.css` (nur die Setup-Seite).
- `public/assets/i18n/<sprache>/<thema>.json`: Texte nach Thema (`common`, `fields`, `errors`, `password`, `setup`, `login`, ...). Jede Seite nennt im `body` mit `data-i18n-files`, welche Themen sie lädt. Eine neue Sprache ist ein neuer Ordner mit denselben Dateinamen.
- `public/assets/vendor/`: Fremdbibliotheken, mit Herkunft und Lizenz.
- `nginx/`: Webserver-Konfiguration. `tests/`: Prüfungen (`check-i18n.js`).

**Warum Sprachdateien nach Thema:** Mit einer Datei pro Sprache hätte jede Seite alle Texte geladen und jede Änderung hätte dieselbe lange Datei berührt. Jetzt lädt eine Seite nur ihre Themen, und `tests/check-i18n.js` prüft, dass en und de dieselben Dateien und Schlüssel haben und dass jede Seite ihre Texte findet.

## Kosten des Umbaus (ehrlich)

Der Umbau hat keine Logik geändert, abgesichert durch die vorhandenen Tests (99 vor dem Umbau, alle weiter grün). Er hat dafür viele Dateien verschoben, in Git sieht man das als Umbenennungen. Die Adressen der API, die Seiten-Adressen und die Datenbank sind unverändert.
