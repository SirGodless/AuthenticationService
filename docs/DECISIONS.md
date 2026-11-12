# Entscheidungen und Annahmen

Stand: Etappe 2 (12.11.2026). Alles hier mit **[A]** wartet auf Joshuas Bestätigung. **[U]** heißt: nicht oder nicht ganz verifiziert.

## Etappe 1

### Technik (kleine Entscheidungen nach Abschnitt 0 Punkt 2)

- **[A] Maven** als Build-Werkzeug. Die Cloud-Umgebung hat Maven 3.9 vorinstalliert, Joshua braucht nichts davon, weil der Docker-Build alles erledigt.
- **Versionen, geprüft gegen Maven Central am 12.11.2026:** Spring Boot **4.1.2** (letzte stabile 4.1.x), darin Spring Security **7.1.2**, Flyway 12.4.0, PostgreSQL-Treiber 42.7.14, Testcontainers 2.0.5. Java 21 (Boot 4 verlangt mindestens 17). BouncyCastle 1.87 wird von Hand festgelegt, weil Spring Security es für Argon2 braucht und es nicht im Boot-BOM steht.
- **Authorization Server:** Er ist in Spring Security 7 aufgegangen (`spring-security-oauth2-authorization-server`, Version 7.1.2 vorhanden). Er ist in Etappe 1 noch nicht eingebunden, dafür ist Etappe 3. **[U]** Auf Maven Central gibt es für Boot 4.1.2 zwei Starter, `spring-boot-starter-oauth2-authorization-server` und `spring-boot-starter-security-oauth2-authorization-server`. Die Spring-Doku nennt den ersten. Welcher der aktuelle und welcher ein Alias ist, kläre ich zu Beginn von Etappe 3.
- **[A] `JdbcClient` statt JPA/Hibernate.** SQL ist sichtbar und für Joshua lesbar, es gibt keine versteckte Magie. Die Datenmenge ist klein.
- **[A] PostgreSQL 17.11, Flyway-Migrationen** in `src/main/resources/db/migration`. Tests laufen gegen ein echtes PostgreSQL (Testcontainers), weil Sperren und Transaktionen sonst nicht echt geprüft werden.
- **[A] Health-Port 8081** (nur intern) für den Docker-Healthcheck. Caddy leitet nur `/api/*` und die OIDC-Pfade an das Backend, deshalb ist `/actuator` von außen nicht erreichbar.
- **[A] Fehler als Codes.** Die API liefert nur `{"error":"<code>"}`. Die Texte (Englisch und Deutsch) stehen in den Sprachdateien der UI. So steht kein Fließtext fest im Backend. Mails (ab Etappe 2) bekommen eigene Sprachdateien im Backend.

### Datenmodell

- **[A] Personenbezogene Daten getrennt:** `users` (UUID, Username, Sprache, Bann, `invited_by`), `user_pii` (E-Mail, bestätigt), `user_credentials` (Passwort-Hash), `user_totp`, `recovery_codes`. Eine spätere Anonymisierung muss so nur `user_pii` und die Zugangsdaten entfernen. Die Funktion selbst wird nicht gebaut (Abschnitt 7).
- **[A] Rechte und Rollen:** Tabellen `permissions` (Typ, Richtung, "unbegrenzt erlaubt", "per Link vergebbar"), `roles`, `user_roles`. Die eingebaute Rolle `Admin` hat das Flag `all_admin_permissions`: Sie enthält implizit jedes Recht mit Präfix `admin.`, auch später neue, ohne dass Zeilen nachgepflegt werden müssen. Die Startliste der Rechte aus Abschnitt 5 ist per Migration angelegt. `role_permissions` und direkte Rechte pro User kommen mit Etappe 4.
- **[A] Protokoll:** Tabelle `audit_log` ist da, das Setup schreibt hinein. Das Leeren der IP nach 90 Tagen und der Schutz gegen Änderungen (nur anhängen) folgen in Etappe 2.

### Setup und erster Admin

- **[A] Zwei Aufrufe:** `prepare` erzeugt das TOTP-Geheimnis und speichert es verschlüsselt am Link, `complete` legt Konto, Rolle, 2FA und Codes in einer Transaktion an. Ohne gültigen Code aus der App entsteht kein Admin. Fehlgeschlagene Versuche verbrauchen den Link nicht (Formularfehler), erst der Erfolg verbraucht ihn.
- **[A] Gleichzeitigkeit:** Alle Setup-Schritte laufen hinter einer Datenbank-Sperre (`pg_advisory_xact_lock`) plus Zeilensperre am Link. Der Test startet 8 parallele Anfragen, genau eine gewinnt.
- **[A] Die E-Mail des ersten Admins gilt als bestätigt.** Begründung: Wer den Setup-Link aus dem Server-Log hat, ist der Betreiber, und Mailversand ist beim ersten Start vielleicht noch nicht eingerichtet. Ohne das gäbe es kein Login. Bitte bestätigen.
- **[A] Tokens:** 256 Bit Zufall, gespeichert als SHA-256. Bei einem Neustart ohne Admin wird ein neuer Link erzeugt und alle offenen alten werden ungültig. Ungültig, abgelaufen, benutzt und "Admin existiert schon" geben dieselbe Antwort (404, `invalid_link`).
- **[A] CSRF:** Für `/api/setup/**` ausgeschaltet. Es gibt keine Sitzung und kein Cookie, allein der geheime Token im Pfad schützt den Aufruf. Alles andere im Backend ist geschlossen (`denyAll`), bis die Endpunkte kommen.
- **[A] Wiederherstellungscodes:** 10 Stück, je 80 Bit Zufall (Format `XXXX-XXXX-XXXX-XXXX`), gespeichert als SHA-256. Ein langsamer Hash bringt bei 80 Bit Zufall nichts, bei kürzeren Codes (z. B. 50 Bit) wäre er nötig gewesen.
- **[A] TOTP:** Eigene Umsetzung (gut 30 Zeilen), geprüft gegen die Testvektoren aus RFC 6238 (SHA-1, 6 Stellen, 30 s). Fenster ±1 Schritt, jeder Schritt gilt nur einmal (`last_used_step`). Geheimnisse: AES-256-GCM, Schlüssel aus `TOTP_ENCRYPTION_KEY`. Der Schlüssel darf nach dem Setup nicht mehr geändert werden.
- **[A] Passwort:** Argon2id, Standard 64 MiB, 3 Durchläufe, Parallelität 1 (über dem OWASP-Minimum). Der Dienst startet nicht mit weniger als 19 MiB und 2 Durchläufen. OWASP nennt auch den gleichwertigen Satz 46 MiB mit 1 Durchlauf. Der wird hier bewusst nicht erlaubt, weil Abschnitt 3 die Mindestkonfiguration auf 19 MiB und 2 Durchläufe festlegt. Beim Start steht im Log, wie lange ein Hash dauert (auf dem Cloud-Rechner mit 19 MiB und 2 Durchläufen etwa 150 ms). Auf dem Zielsystem prüfen.
- **[A] Passwortregeln in Etappe 1:** 15 bis 128 Zeichen, gezählt in Zeichen (nicht UTF-16-Einheiten), keine Zusammensetzungsregeln. **Die Sperrliste fehlt noch**, sie kommt in Etappe 2. Der erste Admin wird in Etappe 1 also nicht dagegen geprüft.
- **[A] IP im Protokoll:** Aktuell die Adresse, die beim Backend ankommt, also die von Caddy. Die echte Client-IP (nur von Caddy übernehmen, sonst fälschbar) wird in Etappe 5 für die Link-Sperre gebraucht und dort sauber gelöst.

### UI (UserHub)

- **[A] Webserver: nginx-unprivileged** (Abschnitt 2 überlässt die Wahl dem Entwickler). Begründung: Das Image läuft ab Werk ohne Root-Rechte, lauscht auf Port 8080 per HTTP, braucht für diese Aufgabe nur eine kleine Konfiguration (Header, ein Pfad-Muster für `/setup/<token>`) und ist weit verbreitet. Ein schlanker Caddy wäre auch möglich gewesen, aber Joshua will sich mit Caddy-Konfiguration nicht weiter befassen. Image: `nginxinc/nginx-unprivileged:1.30-alpine` (nginx 1.30.6). **[U]** Ein Tag mit Patch-Version (`1.30.6-alpine`) konnte ich nicht prüfen, Docker Hub hat in der Sitzung das Abfragelimit gemeldet.
- **[A] QR-Code:** `qrcode-generator` 2.0.4 (MIT) liegt selbst gehostet in `public/assets/vendor`, kein CDN. Das Geheimnis verlässt die Seite nicht.
- **[A] Sicherheits-Header** der UI: strenge CSP (`default-src 'none'`, Skripte und Styles nur von der eigenen Adresse, `frame-ancestors 'none'`), `no-referrer`, `nosniff`, keine Zwischenspeicherung der Setup-Seite.
- **[A] Sprache:** Standard Englisch, Umschalter oben rechts, Wahl nur im Browser gemerkt. Bei Abschluss des Setups wird die gewählte Sprache als Account-Sprache gespeichert.
- Styling ist bewusst schlicht, CSS-Variablen, Dark Mode zuerst. Der Stil kommt in Etappe 6.

### Container und Deployment

- **[A] Feste Images:** `postgres:17.11-alpine`, `axllent/mailpit:v1.29.7`, nginx 1.30-alpine (Minor-Version fest). Die Java-Images (`maven:3.9-eclipse-temurin-21-alpine`, `eclipse-temurin:21-jre-alpine`) sind auf Hauptversion festgelegt, damit Sicherheitsupdates beim Neubauen ankommen.
- **[A] Nicht-Root:** Backend (UID 10001) und UI (nginx, UID 101) laufen ohne Root. Beim PostgreSQL-Image startet der Einstiegspunkt kurz als Root und gibt dann Rechte ab, der Datenbankprozess läuft als `postgres`.
- **[A] Netzwerke:** `caddy_net` (extern, gehört Caddy) für Backend und UI, `internal` (nur intern, ohne Zugang von außen) für die Datenbank und Mailpit. Der Name `caddy_net` und der Ablageort `/opt/containers/` stammen aus Joshuas Infrastruktur-Notizen und sind **[U]** nicht von mir geprüft.
- **Ablage der Repos in der Cloud-Sitzung (geprüft):** `AuthenticationService/` und `UserHub/` liegen nebeneinander in `/home/user`. `docker-compose.yml` baut die UI aus `../UserHub`, das funktioniert.
- **Nur in der Cloud-Sitzung nötig, nicht im Repo:** Der Docker-Build im Container scheiterte hier an einem unbekannten Zertifikat des Sandbox-Proxys (PKIX), nicht am Dockerfile. Ich habe mit einer Kopie des Dockerfiles getestet, die das Proxy-Zertifikat importiert. Auf Joshuas Server tritt das nicht auf. Er sollte beim ersten `docker compose up -d --build` dennoch prüfen, dass der Build durchläuft.
- **[A] Branches:** Beide Repos arbeiten auf dem von der Sitzung vorgegebenen Branch `claude/relaxed-feynman-ng4azc`. Abschnitt 0 verlangt einen Branch pro Etappe. Siehe offene Frage in `OPEN-QUESTIONS.md`.

## Etappe 2

Von Joshua bestätigt (12.11.2026): Branches sind gemerged, die E-Mail gilt **nur beim Setup-Link des ersten Admins** als bestätigt (so umgesetzt, alle anderen Konten müssen per Mail bestätigen), ein **eigener Mailserver** gehört in den Stack, Backup inklusive `TOTP_ENCRYPTION_KEY`.

### Anmeldung und Sitzungen

- **[A] Eigene Sitzungstabelle** (`login_sessions`) statt Spring Session. So lassen sich Sitzungen eines Users gezielt beenden (Reset, später Bann) und die Laufzeiten genau steuern. Das Cookie enthält 256 Bit Zufall, in der Datenbank steht nur der SHA-256.
- **[A] Cookie:** `__Host-sid` (der Browser erzwingt damit Secure, Pfad `/` und kein Domain-Attribut, also nur für `auth.anfall.net` selbst), `HttpOnly`, `Secure`, `SameSite=Lax`. Mit "Eingeloggt bleiben" 30 Tage und persistent, sonst Sitzungs-Cookie mit 12 Stunden auf dem Server. Alle Werte einstellbar (`app.session.*`). Im Browser geprüft (Playwright).
- **[A] Die Laufzeiten 12 Stunden und 30 Tage** aus Abschnitt 3 gelten hier für die Anmeldesitzung im Browser. Die Token-Laufzeiten (10 Minuten Access-Token, Refresh-Token) kommen mit Etappe 3 und hängen dort an dieser Sitzung.
- **[A] CSRF:** Cookie `XSRF-TOKEN` (für die UI lesbar, `Secure`, `SameSite=Lax`), die UI schickt ihn als Header `X-XSRF-TOKEN` zurück. Ohne Header 403. Ausgenommen ist nur `/api/setup/**` (dort schützt der geheime Token im Pfad). **Die UI muss auf derselben Adresse wie die API laufen** (`auth.anfall.net`, Caddy verteilt die Pfade). Für den Hub auf `anfall.net` ist das offen (Frage "Hub-UI an der API", vor Etappe 3).
- **[A] Zwei Schritte bei 2FA:** Nach dem Passwort entsteht eine wartende Sitzung (5 Minuten, zählt nicht als angemeldet). Erst der Code erzeugt eine **neue** Sitzung (gegen Session Fixation).
- **[A] Antworten verraten nichts:** Falsches Passwort und unbekannter Username geben dieselbe Antwort und rechnen gleich lange (Dummy-Hash). "E-Mail nicht bestätigt" und "gesperrt" werden erst nach **richtigem** Passwort gemeldet.
- **[A] Passwort ändern** (angemeldet, aktuelles Passwort nötig): beendet die anderen Sitzungen, die aktuelle bleibt. Der Reset per Mail beendet alle.
- **[A] TOTP-Code nur einmal:** Jeder 30-Sekunden-Schritt gilt einmal (bedingtes UPDATE, auch bei gleichzeitigen Anfragen). Im Login gibt man den 6-stelligen Code oder einen Wiederherstellungscode in dasselbe Feld ein.

### Limits

- **[A] Login:** 5 Fehlversuche je Konto in 15 Minuten, dann 15 Minuten Pause, auch für das richtige Passwort. Für 2FA-Codes ein eigener Zähler mit denselben Werten. Gezählt wird am Konto (Username und E-Mail sind dasselbe Konto), bei unbekannten Kennungen an der Kennung, damit die Sperre nichts über die Existenz verrät. Ein Erfolg setzt den Zähler zurück. Nachteil, den die Vorgabe mitbringt: Wer einen Usernamen kennt, kann das Konto 15 Minuten sperren.
- **[A] Mail-Anfragen (Reset, Bestätigung erneut senden):** 3 pro Stunde **je E-Mail und je IP, getrennt gezählt** (nach der Vorgabe "je E-Mail und IP" ausgelegt als beide Grenzen einzeln). Auch unbekannte Adressen zählen, deshalb darf die Antwort bei Überschreitung ein ehrliches 429 sein, ohne etwas zu verraten.
- Zähler stehen in der Datenbank (überleben Neustarts). Der Schlüssel ist ein Hash, es liegen keine E-Mail-Adressen oder IPs im Klartext in `throttle`. IPv6 wird auf das /64-Netz gekürzt.
- **[A] Echte IP:** `X-Forwarded-For` zählt nur, wenn die Anfrage aus einem vertrauenswürdigen Bereich kommt (Standard: private Adressen, also das Docker-Netz mit Caddy), und dann der **letzte** Eintrag (Caddy hängt die echte Adresse an). Von anderen Absendern wird der Header ignoriert. Schwäche: Jeder Container im Netz `caddy_net` könnte den Header fälschen. Bei Bedarf `app.trusted-proxies` auf die feste Caddy-Adresse einengen.

### Passwörter

- **[A] Unicode-Normalisierung (NFKC)** vor Prüfung, Sperrliste und Hash (NIST SP 800-63B empfiehlt das). Dasselbe Passwort von verschiedenen Tastaturen wird gleich behandelt.
- **Sperrliste:** NCSC-Liste (99.840 Einträge) aus SecLists, festgenagelter Commit mit SHA-256-Prüfsumme, wird beim Docker-Build per `scripts/fetch-blocklist.sh` geladen, nicht im Git. Es wird das ganze Passwort verglichen. Austauschbar über das Interface `PasswordBlocklist`. **Ehrliche Einschätzung:** Von den 99.840 Einträgen sind nur 359 mindestens 15 Zeichen lang. Mit der 15-Zeichen-Regel fängt die Liste also kaum noch etwas, was die Länge nicht schon abfängt. Sie schadet nicht und ist so vorgegeben. Der Pwned-Passwords-Index (viel größer, mit langen Passwörtern) würde mehr bringen.
- Fehlt die Datei, startet der Dienst nicht (lieber ein klarer Fehler als still ohne Sperrliste).

### Mail

- **Eigener Mailserver (von Joshua entschieden):** Container `auth-mail`, Image `boky/postfix` (MIT, README geprüft) **per Digest festgenagelt**, DKIM über rspamd (Standard des Images). Nur Versand, keine veröffentlichten Ports. Netzwerke: `internal` (Backend übergibt Mails) und `mail_out` (normaler Zugang nach draußen zum Ausliefern). Die Warteschlange (`/var/spool/postfix`) und der DKIM-Schlüssel liegen in Volumes. Das Image startet als Root (Postfix braucht das), das Backend läuft ohne Root. Das Image bringt einen eigenen Healthcheck mit.
- Im Test geprüft: Das Backend übergibt per SMTP an den echten Container (EHLO, MAIL, RCPT laufen), und eine über Submission eingelieferte Mail trägt eine gültige DKIM-Signatur (`d=anfall.net; s=mail`). **Nicht prüfbar in der Sitzung:** die Auslieferung nach draußen (kein Port 25, kein DNS für fremde Domains) und die Bewertung durch Gmail oder Outlook. Das testet Joshua nach dem Deployment.
- **[A] Mails sind reiner Text**, Texte in `mail.properties` (Englisch) und `mail_de.properties` (Deutsch), Sprache des Accounts. Versand im Hintergrund und erst nach dem Commit der Datenbank: So dauert die Antwort bei "Passwort vergessen" gleich lang, egal ob es die Adresse gibt, und ein Link zeigt nie auf einen Token, den ein Rollback gelöscht hat.
- **[A] Ohne Warteschlange in der Anwendung:** Klappt die Übergabe an den Mailserver nicht, steht ein Fehler im Log (ohne Adresse und Link) und der User fordert die Mail neu an. Eine Outbox in der Datenbank hätte Links im Klartext gespeichert. Ist die Mail beim Mailserver, wiederholt Postfix selbst.
- **[A] Links in Mails verbrauchen sich erst auf Knopfdruck**, nicht beim Öffnen der Seite (Mail-Scanner und Vorschauen öffnen Links). Das gilt für Bestätigung und Reset. Die Seite prüft den Link vorher nur und verbraucht ihn nicht.
- **[A] Gültigkeit:** Bestätigung 24 Stunden, Reset 1 Stunde, beide einmalig, einstellbar. Eine neue Anfrage macht ältere offene Links desselben Zwecks ungültig. Reset-Mails gehen nur an bestätigte Adressen von nicht gesperrten Konten.
- **Noch keine Registrierung:** Konten entstehen erst mit den Einladungslinks (Etappe 5). Die Bausteine (`AccountService`, `EmailConfirmationService.sendFor`) sind fertig und in den Tests benutzt. Es gibt deshalb in Etappe 2 keinen Weg, im Betrieb ein unbestätigtes Konto zu erzeugen.

### Protokoll

- **[A] Nur anhängen, in der Datenbank erzwungen:** Ein Trigger verbietet `DELETE` und jede Änderung außer dem Leeren der IP. Das Leeren nach 90 Tagen erledigt ein stündlicher Aufräum-Job (außerdem: abgelaufene Sitzungen, alte Zähler, alte Mail-Tokens nach 7 Tagen). Für eine spätere manuelle Anonymisierung muss Joshua den Trigger bewusst deaktivieren.
- Protokolliert werden Logins (Erfolg, Fehlschlag, gesperrt, abgelehnt) mit IP, Abmelden, Passwort geändert oder zurückgesetzt, Reset angefordert, E-Mail bestätigt, Limits. **Nie** Passwörter, Tokens, Codes und auch nicht die eingegebene Kennung (kann aus Versehen ein Passwort sein). Fehlereinträge werden in einer eigenen Transaktion geschrieben, damit sie auch bei abgebrochenen Anfragen bleiben.

### Betreiber-Befehl

- `auth-admin reset-2fa <username>` im Backend-Container (siehe README). Startet das Backend ohne Webserver, entfernt 2FA und Wiederherstellungscodes, beendet alle Sitzungen, protokolliert mit `ip=cli`.
- **Lücke bis Etappe 6:** Ein Konto ohne 2FA kann sie erst über die Profilseite wieder einrichten. Die API dafür (und das Abschalten durch normale User) gibt es noch nicht. Siehe offene Fragen.

### Tests und Prüfungen

- 99 automatische Tests, darunter Rennen beim gleichzeitigen Einlösen eines Reset-Links, alle Limits, Sitzungslaufzeiten, Cookie-Eigenschaften, 2FA-Wiederverwendung, Neutralität der Antworten, Protokollschutz, Unicode. Der Mailversand läuft im Test über einen echten SMTP-Server (GreenMail).
- Ende-zu-Ende im Browser (`tests/e2e/run.sh`) mit Test-Caddy über HTTPS: Setup, Login mit 2FA, falscher Code, "Passwort vergessen" mit Mail aus Mailpit, Sperrlisten-Fehler, Reset, Wiederherstellungscode, Abmelden, Cookie-Flags im echten Browser.
- Lizenzen: siehe `docs/THIRD-PARTY.md`.

## Nicht in Etappe 1 gebaut (bewusst, Stand Etappe 1)

- Befehl im Backend-Container, der die 2FA des letzten Admins zurücksetzt (Abschnitt 3). Er braucht Login und 2FA-Prüfung und kommt mit Etappe 2.
- Sperrliste, Login, Mail, Limits, Löschen des IP-Feldes nach 90 Tagen: Etappe 2.
- Pflicht-2FA "vor jeder Admin-Funktion": Es gibt noch keine Admin-Funktion. Das Setup erzwingt die 2FA beim Anlegen.
