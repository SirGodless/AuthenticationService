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
| `password` | Passwort-Regeln und Hashing, Passwort vergessen, Passwort ändern |
| `password/check` | Die einzelnen Prüfungen (Länge, Sperrliste, Kontextwörter) |
| `emailconfirmation` | E-Mail-Adresse bestätigen, Bestätigungsmail erneut senden |
| `emailtoken` | Einmal-Links per Mail (gemeinsam von Bestätigung und Reset genutzt) |
| `twofactor` | TOTP und Wiederherstellungscodes |
| `setup` | Setup-Link und erster Admin |
| `admincli` | Befehle für den Betreiber im Container |
| `audit` | Protokoll |
| `ratelimit` | Zähler und Sperren |
| `mail` | Mailversand und Mail-Texte |
| `security` | Zugriffsregeln und CSRF-Schutz |
| `crypto` | Zufall, Hashes, Verschlüsselung (fachthemenübergreifend) |
| `config` | Einstellungen und Startprüfungen |
| `web` | Gemeinsame Teile der API (Fehler, Antworten, Client-IP) |

Spätere Etappen bekommen ihre eigenen Pakete: `invite` (Einladungslinks), `permission` (Rechte und Rollen), `oidc` (Provider, Clients).

**Aufteilung großer Klassen.** Vorher waren `AuthController` (8 Schnittstellen), `LoginService` (Passwort, 2FA, TOTP-Prüfung, Abmelden), `SetupService` (Link und Admin) und `MaintenanceJobs` (4 Aufräumarbeiten) Sammelklassen. Jetzt hat jede Klasse eine Aufgabe, im Namen erkennbar:

- Schnittstellen: ein Controller je Seite der UI (`LoginController`, `PasswordResetController`, `PasswordChangeController`, `EmailConfirmationController`, `CurrentUserController`). Die Adressen der API sind unverändert.
- Logik: `LoginService` (erster Schritt), `SecondFactorLoginService` (zweiter Schritt), `SecondFactorChecker` (Codes prüfen), `SetupTokenService` (Link), `FirstAdminService` (Admin anlegen).
- SQL für Konten steht gesammelt in `AccountRepository`. Vorher hatten drei Klassen dieselben Abfragen.
- Aufräum-Jobs: je eine kleine Klasse beim zugehörigen Fachthema, Name endet auf `Job` (`AuditIpRetentionJob`, `ExpiredSessionsJob`, `ThrottleCleanupJob`, `EmailTokenCleanupJob`).
- Mail-Texte liegen in einer Datei pro Mail und Sprache (`resources/mail/`), die Textsuche in `MailTexts`.

**Tests spiegeln die Pakete.** Zu jedem Paket gibt es einen Testordner gleichen Namens, und jede Testklasse prüft ein Thema (`LoginLimitsTest`, `SecondFactorLoginTest`, `SessionCookieTest`, ...). Gemeinsame Hilfen (Testdatenbank, Test-Mailserver, Browser-Simulation) stehen in `testsupport/`.

**Migrationen.** Eine Migration pro Änderung, mit sprechendem Namen. **[A]** Die beiden bestehenden Migrationen (`V1__base_schema.sql`, `V2__login_sessions_tokens_throttle.sql`) bleiben unverändert, auch wenn sie mehrere Themen mischen. Eine Migration, die schon auf einer Datenbank gelaufen ist, darf man nicht ändern, weil Flyway die Prüfsumme prüft und den Start verweigert. Da ich nicht weiß, ob Joshua den Stack schon gestartet hat, schneide ich sie nicht um. **Ab jetzt gilt: eine Migration je Fachthema und Änderung** (z. B. `V3__invite_links.sql`). Wenn Joshua bestätigt, dass noch keine Datenbank existiert, kann man V1 und V2 noch vor dem ersten Deployment nach Themen schneiden.

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
