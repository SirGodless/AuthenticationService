# Absicherung und Auslieferung (Etappe 7)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

## Entscheidungen von Joshua (18.11.2026)

- **[E]** Der Hub darf vorerst unter `auth.anfall.net` laufen. `anfall.net` und `www.anfall.net` leiten dorthin um. Damit entfällt CORS ganz: Seiten und API haben dieselbe Adresse, das Sitzungs-Cookie ist host-only für `auth.anfall.net`.
- **[E]** Die Darstellung (hell, dunkel, wie das Gerät) ist eine Einstellung im Konto (Profilseite), kein Umschalter auf jeder Seite.
- **[E]** Die vom Dienst gemeldete Farbe überschreibt die im Hub eingestellte, außer im Hub ist festgehalten, dass sie es nicht soll (Kästchen). Siehe `hub-and-services.md`.
- **[E]** Die Dienste sollen weiter direkt aufrufbar sein, und wer im Hub angemeldet ist, soll ohne erneute Anmeldung in den Diensten sein (wie bei Google). Siehe unten.

## Einmal anmelden, überall drin (Single Sign-on)

Das gibt der OIDC-Provider schon her, es war nur nicht als Ziel festgehalten. Es gibt **eine** Anmeldesitzung beim Provider (Cookie `__Host-sid` auf `auth.anfall.net`). Hub und Dienste nutzen dieselbe:

- Ruft jemand einen Dienst direkt auf, schickt dieser per OIDC zu `/oauth2/authorize`. Ohne Sitzung geht es zur Login-Seite mit Rücksprung, mit Sitzung sofort zurück zum Dienst mit einem Code. Der Hub ist nie nötig.
- Für jeden Dienst wird das Recht einzeln geprüft: Angemeldet heißt nicht berechtigt (Seite "Kein Zugang"). Ein gebannter User bekommt von keinem Dienst einen Code.
- Abmelden im Hub beendet die Sitzung beim Provider: Danach fragen alle Dienste wieder nach der Anmeldung.
- Getestet in `SingleSignOnTest` (4 Fälle, mit zwei Diensten und einer Sitzung).

**Grenzen (**[U]**, hängen von den Diensten ab):** Der Dienst hat danach seine **eigene** Sitzung, die vom Abmelden im Hub nicht automatisch endet (Single Logout ist nicht festgelegt, siehe `OPEN-QUESTIONS.md`). Wie lange sie hält und wie schnell ein Entzug von Rechten oder ein Bann dort wirkt, entscheiden die Dienste (Abschnitt 6 der Spezifikation). Ob sich Vaultwarden, Audiobookshelf und Pelican wie gedacht verhalten, ist erst nach dem Deployment prüfbar. Die Anbindung der echten Dienste ist weiter zurückgestellt.

## Sicherheits-Header und CORS (Abschnitt 10, geprüft)

- **Seiten der UI** (nginx): CSP mit `default-src 'none'` und `frame-ancestors 'none'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`. Im Browser-Test für alle 11 Seitenarten geprüft (Hub, Login, Passwort vergessen, Kein Zugang, Dashboard, Profil, 2FA, Links, Registrierung, Reset, Bestätigung). Keine Konsolenfehler oder CSP-Verstöße.
- **HSTS** setzt Caddy (`deploy/Caddyfile.snippet`, ein Jahr, `includeSubDomains`). Der Browser-Test läuft durch einen Test-Caddy, der aus genau diesem Block gebaut wird.
- **API** (Backend): `nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store` (Test `ResponseHeadersTest`).
- **CORS:** Nirgends freigegeben. Weder eine Anfrage mit fremdem Origin noch ein Preflight bekommt `Access-Control-Allow-Origin` (getestet für API, Admin, Registrierung, JWKS und Discovery). Das ist gewollt, weil Seiten und API dieselbe Adresse haben. Wird der Hub später unter einer anderen Adresse betrieben, kommt CORS für genau diese Adresse dazu (siehe `hub-ui-authentication.md`).
- **Cookies:** `__Host-sid` mit `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/` und ohne `Domain` (host-only), CSRF-Cookie `Secure`, `SameSite=Lax`. Im echten Browser und im Test geprüft.
- **Nichts Geheimes in Antworten oder Log:** Passwörter, Tokens, TOTP-Geheimnisse und Wiederherstellungscodes stehen nicht im Protokoll (Tests in `AuditAdminApiTest`, `TwoFactorSettingsTest`, `InviteRegistrationTest`).
- **Keine Tokens im Browser-Speicher:** Die UI hat nur das HttpOnly-Cookie. Im Browser wird nur die Wahl der Darstellung und der Sprache gemerkt (`localStorage`).

## Caddy (`deploy/Caddyfile.snippet`)

Zwei Blöcke: `auth.anfall.net` (Backend-Pfade an `auth-backend:8080`, alles andere an `userhub-ui:8080`, HSTS, Kompression) und `anfall.net, www.anfall.net` (dauerhafte Umleitung auf `auth.anfall.net`). Mit `caddy validate` geprüft, und der Browser-Test nutzt den echten Block (Host und Zertifikat ersetzt). Der Verwaltungs-Port 8081 wird nicht weitergegeben. Caddy setzt `X-Forwarded-For` auf die echte Adresse, das Backend glaubt dem nur von `TRUSTED_PROXIES`.

## Backup (**[E]** Konzept, Umsetzung **[A]**)

Container `auth-backup` (Verzeichnis `backup/`, Basis das Postgres-Image, Benutzer ohne Root-Rechte):

- **Zeitplan:** Eine Schleife sieht alle 30 Sekunden nach, ob für den heutigen Tag (Zeitzone `BACKUP_TZ`, **[A]** Europe/Berlin) schon ein Backup existiert, sonst macht sie eines. Das ergibt "jeden Tag um 0 Uhr", übersteht Neustarts, Zeitumstellungen und eine kurz nicht erreichbare Datenbank (neuer Versuch nach 5 Minuten) und liefert beim ersten Start sofort ein Backup. Warum keine Cron-Zeile: Dafür müsste ein Cron-Dienst im Container laufen, und ein verpasster Lauf wäre verloren.
- **Aufbewahrung:** täglich nach 7 Tagen gelöscht, monatlich (am Ersten) nach einem Jahr. Das Datum steht im Dateinamen, die Rechnung nutzt es (nicht die Dateizeit). Getestet mit 400 nachgespielten Tagen: genau 7 tägliche und 12 monatliche Dumps, der älteste tägliche ist der von vor 6 Tagen.
- **Inhalt:** Datenbank-Dump, die Schlüssel `TOTP_ENCRYPTION_KEY` und `OIDC_KEY_ENCRYPTION_KEY` (**[E]**, ohne sie sind TOTP-Geheimnisse und Signierschlüssel nach einer Wiederherstellung unlesbar) und der DKIM-Schlüssel. **Nicht** der Pepper. Dateien nur für den Besitzer lesbar. **Hinweis:** Dump und die beiden Schlüssel liegen damit zusammen im selben Ordner, wer ihn lesen kann, kann TOTP-Geheimnisse entschlüsseln. Das ist die Folge der Entscheidung, die Schlüssel ins Backup zu legen.
- **Ziel:** Ordner auf dem Server (**[E]**, `BACKUP_DIR`, Standard `./backups`).
- **Schutz vor halben Dumps:** erst in eine Zwischendatei, dann umbenennen. Ein fehlgeschlagener Dump ändert nichts und lässt den Container nach 36 Stunden `unhealthy` werden.
- **Wiederherstellung** getestet (Daten ändern, Dump zurückspielen, Daten wieder da): `backup/restore.sh`.
- Im Browser-Test läuft der Container im echten Stack mit: Dump, Schlüsseldatei und DKIM-Sicherung entstehen, der Container ist gesund.

## Feste Versionen und Schwachstellen (Abschnitt 10)

- **Images:** Alle Basis-Images (Backend-Build und -Laufzeit, UI, Backup, Datenbank, Mailpit) sind per Digest festgenagelt (Stand 18.11.2026). Der Mailserver war es schon. Wie man sie aktualisiert, steht in `DEPLOY.md`.
- **Java-Abhängigkeiten:** Spring Boot 4.1.2 ist die neueste 4.1-Version auf Maven Central (4.2.0 ist erst Release Candidate). Das Projekt nagelt Boot per Parent fest, die übrigen Versionen kommen von dort (Ausnahmen mit eigener Version: BouncyCastle 1.87, GreenMail).
- **[U] Schwachstellenprüfung nicht möglich:** Die Datenbanken für bekannte Schwachstellen (OSV, GitHub Advisories) sind aus der Entwicklungsumgebung nicht erreichbar (Zugriff vom Proxy abgelehnt). Eine Prüfung gegen bekannte Schwachstellen ist deshalb **nicht erfolgt**. Auffällig beim Abgleich mit Maven Central: Das mit Boot gelieferte `tomcat-embed-core` ist 11.0.27, neuer ist 11.0.29 (Patchstand, Inhalt der Updates nicht geprüft). **Vor dem Release auf dem Server** am besten `mvn org.owasp:dependency-check-maven:check` oder die Abhängigkeitsprüfung von GitHub einschalten.
- **UI:** Einzige Fremdbibliothek ist `qrcode-generator` 2.0.4 (selbst ausgeliefert, MIT). Es gibt keine npm-Abhängigkeiten im Betrieb und kein CDN.

## Darstellung als Kontoeinstellung

`users.theme` (`system`, `light`, `dark`, Standard `system`), geändert über `PUT /api/auth/theme` auf der Profilseite. Nach dem Login übernimmt die Oberfläche die Einstellung des Kontos und merkt sie im Browser, damit auch die Seiten vor dem Login (Login, Registrierung, ...) und der erste Aufbau ohne Aufblitzen zur letzten Wahl passen. Ohne Wahl folgt die Seite dem Gerät. **[A]** Auf einem fremden Browser, in dem noch niemand angemeldet war, gilt vor dem Login das Gerät.
