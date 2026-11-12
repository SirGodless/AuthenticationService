# CLAUDE.md: AuthenticationService (Backend) und UserHub (UI)

Stand: 12.11.2026. Diese Datei ist die verbindliche Spezifikation für beide Repos. Sie liegt in `AuthenticationService`. `UserHub` hat eine Kurzfassung, die hierher verweist. Bei Widerspruch gilt diese Datei.

Kennzeichnung der Aussagen:

- **[E]** von Joshua entschieden. Nicht ohne Rückfrage ändern.
- **[A]** Annahme, von Claude vorgeschlagen und von Joshua noch nicht bestätigt. Umsetzen, in `docs/DECISIONS.md` auflisten, nach der Etappe zur Bestätigung vorlegen.
- **[O]** offen oder bewusst zurückgestellt. Nicht bauen und nicht raten, sondern nachfragen.
- **[U]** nicht verifiziert. Vor dem Verlassen darauf gegen die offizielle Doku prüfen und Ergebnis notieren.

## 0. Arbeitsregeln für Claude Code

1. Sprache: Antworten, Doku und Kommentare auf Deutsch, Bezeichner im Code auf Englisch. UI-Texte und Mails gibt es auf Englisch (Standard) und Deutsch, siehe Abschnitt 8. Keine Gedankenstriche (Em-Dashes) in Texten.
2. Nichts erfinden. Steht etwas nicht in dieser Datei und betrifft Sicherheit, Datenmodell oder Bedienung, nicht raten: in `docs/OPEN-QUESTIONS.md` eintragen und Joshua fragen. Kleine technische Details (Paketnamen, Ordnerstruktur) darfst du entscheiden und in `docs/DECISIONS.md` notieren.
3. Prüfen statt annehmen: Versionen, Konfigurationsnamen und Verhalten von Bibliotheken und Diensten gegen die aktuelle offizielle Doku prüfen, nicht aus dem Gedächtnis. Was sich nicht prüfen ließ, als "nicht verifiziert" kennzeichnen.
4. Widersprechen: Ist eine Vorgabe hier falsch, unsicher oder widersprüchlich, sag es, statt sie stillschweigend umzusetzen.
5. Joshua ist Einzelentwickler und kann Java lesen und verstehen, Syntax hat er nicht auswendig. Code einfach und lesbar halten, kurze Kommentare (Warum, nicht Was), keine unnötige Abstraktion.
6. Arbeitsweise: Du arbeitest in einer Cloud-Sitzung ohne Zugriff auf Joshuas Server. Ein Branch pro Etappe und ein Pull Request. Kein Push auf `main`, kein Force-Push. Nach jeder Etappe: Tests grün, PR, kurze Zusammenfassung mit der Liste der getroffenen Annahmen und offenen Fragen. Die nächste Etappe erst nach Freigabe.
7. Secrets nie im Repo und nie im Log. Nur `.env.example` mit Platzhaltern. Alle Werte in der Cloud-Sitzung sind Dummy-Werte.
8. In der Cloud-Sitzung ist `docker compose` verfügbar. Nicht verfügbar: echter Mailversand, Caddy, die echten Domains und die echten Dienste (Vaultwarden, Audiobookshelf, Pelican). Das testet Joshua nach dem Deployment.

## 1. Ziel und Umfang

[E] Ein neues, zentrales User-Management mit User-Hub für Joshuas selbst gehostete Dienste auf anfall.net. Die bisherige Auth-Infrastruktur wird ignoriert, alles wird neu geschrieben.

- [E] Der Auth-Dienst ist ein OIDC-Provider. Jeder Dienst ist ein OIDC-Client und verlangt eine Berechtigung. Die Zugriffsentscheidung fällt am Provider (Authorize- und Token-Endpoint), nicht in der Hub-Oberfläche. Handy-Apps sprechen direkt mit dem Provider und müssen nicht über den Hub gehen.
- [E] Registrierung nur über Einladungslinks.
- [E] Der Hub zeigt links eine Sidebar mit den Diensten, die der User nutzen darf.
- [E] Die Userbase ist klein. Skalierung ist kein Ziel.

Nicht Teil der aktuellen Etappen: Löschfunktion für Konten, Einbetten der Dienste, Pelican-Anbindung, Passkeys, E-Mail-Änderung (siehe Abschnitt 12).

## 2. Architektur und Deployment

- [E] UI und Backend laufen in getrennten Containern. Alles läuft hinter dem Reverse-Proxy Caddy.
- [E] Backend: Java mit Spring Boot und Spring Authorization Server (kein Kotlin, Joshua soll den Code lesen können). Verwende die aktuelle Version. Laut Spring-Doku ist der Authorization Server in Spring Security 7.0 aufgegangen und 1.5.x ist die letzte eigenständige Generation. [U] Prüfe die aktuelle Doku, bevor du Abhängigkeiten festlegst.
- [E] UI: einfaches HTML, CSS und JavaScript ohne großes Framework.
- [A] Java 21 (in der Cloud-Umgebung vorinstalliert), PostgreSQL als Datenbank in einem eigenen Container, Flyway für Datenbank-Migrationen.
- [E] Der UI-Container liefert die statischen Dateien über einen kleinen Webserver aus. Welcher (z. B. nginx oder ein schlanker Caddy mit `file_server`), entscheidest du und begründest es in `docs/DECISIONS.md`. Joshua kennt Caddy nur als Reverse-Proxy und will sich damit nicht weiter befassen. Entscheidend ist nur: Der Container lauscht intern per einfachem HTTP auf einem festen Port (kein HTTPS, keine veröffentlichten Ports) und ist über `caddy_net` per `reverse_proxy userhub-ui:<port>` erreichbar. Zertifikate und HTTPS übernimmt allein der bestehende Caddy.

Container:

| Container | Aufgabe | Netzwerk |
| :- | :- | :- |
| `auth-backend` | OIDC-Provider, API, Mail, Datenbankzugriff | `caddy_net` und intern |
| `userhub-ui` | Hub, Admin-Dashboard, Login-, Registrierungs- und Setup-Seiten | `caddy_net` |
| `auth-db` | PostgreSQL | nur intern |
| `auth-mail` | Eigener Mailserver (Postfix mit DKIM), nur zum Versenden, keine veröffentlichten Ports | `internal` und `mail_out` |
| `mailpit` (nur Entwicklung, Compose-Profil `dev`) | Test-Mailserver | nur intern |

- [E] Joshua (12.11.2026): Der Stack bringt einen eigenen Mailserver mit (Container `auth-mail`). Ausgehend Port 25 wird auf dem Server freigegeben, die Einrichtung (Firewall, DNS, rDNS) steht in der README.md. Keine externen Mail-Dienste.
- [E] Keine veröffentlichten Ports. Nur Caddy ist von außen erreichbar. [A] Das Netzwerk `caddy_net` und der Ablageort `/opt/containers/` stammen aus Joshuas Infrastruktur-Notizen und sind vor Verwendung zu bestätigen.
- [E] Die beiden Repos liegen nebeneinander (`AuthenticationService/` und `UserHub/` im selben Ordner). Das `docker-compose.yml` liegt in `AuthenticationService` und baut die UI aus `../UserHub`. [U] Prüfe als ersten Schritt, ob die Cloud-Sitzung beide Repos so ablegt. Wenn nicht, melde es, statt eine Umgehung zu bauen.
- [E] Hostnamen: Hub unter `anfall.net` und `www.anfall.net`, Auth unter `auth.anfall.net`. [A] `anfall.net` ist die Hauptadresse, `www` leitet dorthin um. Die Issuer-URL `https://auth.anfall.net` ist fest und darf sich nie ändern, weil sie in allen Clients hinterlegt ist.
- [A] Die Login-Seite muss unter `auth.anfall.net` erscheinen. Caddy verteilt dort die Pfade: `/oauth2/*`, `/.well-known/*`, `/connect/*`, `/userinfo` und `/api/*` an das Backend, alles andere an den UI-Container.
- [E] Caddy: Du änderst die laufende Caddy-Konfiguration nicht. Du lieferst einen Block in `deploy/Caddyfile.snippet`, den Joshua selbst einträgt.
- [E] Deployment: Joshua klont beide Repos in einen Ordner auf dem Server, kopiert `.env.example` nach `.env`, trägt echte Werte ein und startet `docker compose up -d --build`. Schreibe dafür `docs/DEPLOY.md` in einfachen Schritten (Joshua kennt Konzepte, aber nicht jede Syntax auswendig).
- [A] Container laufen als Nicht-Root-User, haben Healthchecks und feste Image-Versionen.
- [O] Backup der Datenbank wurde nicht besprochen. Dokumentiere in `docs/DEPLOY.md` einen Befehl für einen Datenbank-Dump und frage Joshua, wie er Backups haben will.

## 3. Konten, Login, Passwörter, 2FA

Konten und Login

- [E] Jeder User hat eine E-Mail-Adresse. Sie wird bei der Registrierung verlangt und einmalig bestätigt. Ohne Bestätigung kein Zugang.
- [E] Login mit Username oder E-Mail plus Passwort plus optionaler 2FA. Beim Einloggen gibt es "Eingeloggt bleiben".
- [E] Die User-UUID ist die Kennung (`sub`). Die Prüfung von Usernamen ist immer in Kleinbuchstaben. Usernames sind eindeutig und bleiben für immer belegt.
- [A] Erlaubte Zeichen im Username: `a-z`, `0-9`, `.`, `_`, `-`, Länge 3 bis 32 (gegen Lookalike-Zeichen). E-Mail-Adressen werden normalisiert (Kleinbuchstaben) und sind eindeutig.
- [A] Der Bestätigungslink in der Mail gilt einmalig und läuft nach 24 Stunden ab. Erneutes Senden ist begrenzt (3 pro Stunde je E-Mail und IP).
- [A] Angemeldete User können ihr Passwort ändern (aktuelles Passwort nötig).

Passwörter

- [E] Hashing mit Argon2id. Mindestkonfiguration laut OWASP: 19 MiB Speicher, 2 Durchläufe, Parallelität 1. [A] Benchmarke auf dem Zielsystem und wähle nicht niedriger.
- [E] Mindestens 15 Zeichen, keine Zusammensetzungsregeln (kein Zwang zu Groß, Klein, Zahl, Sonderzeichen). Joshua folgt damit NIST SP 800-63B-4.
- [A] Mindestens 64 Zeichen müssen erlaubt sein (Obergrenze 128).
- [E] Abgleich mit einer Sperrliste bekannter oder geleakter Passwörter. Es wird das ganze Passwort verglichen, nicht Teile davon.
- [E] Die Prüfung läuft hinter einer austauschbaren Schnittstelle. Start mit der NCSC-Liste der 100.000 häufigsten geleakten Passwörter (`100k-most-used-passwords-NCSC.txt` aus SecLists). Später kann Joshua den Pwned-Passwords-Index (Have I Been Pwned, offline, rund 35 bis 40 GB oder als kleinerer Index) auf dem Server nutzen. [U] SecLists steht laut einer Drittseite unter MIT-Lizenz, prüfe das im Repo. Lege große Listen nicht ins Git, sondern lade sie per Skript.
- [E] Kein Fremddienst im Registrierungsweg: keine Online-Abfrage von Passwortdaten, keine externen Mail-Dienste.

Passwort-Reset

- [E] Der Reset läuft per Mail, solange die E-Mail bestätigt ist.
- [A] Der Link gilt einmalig und 1 Stunde. Die Antwort ist neutral (verrät nicht, ob die Adresse existiert). Ein Reset beendet alle Sitzungen und Refresh-Tokens und entfernt die 2FA nicht.

2FA

- [E] Nur TOTP (Code aus einer Authenticator-App). Passkeys sind zurückgestellt, Joshua hatte damit bisher Schwierigkeiten.
- [E] 2FA ist für jeden User Pflicht, der mindestens eines der Rechte `admin.*` hat (die Verwaltungsrechte des Hubs, siehe Abschnitt 5; Rechte wie `service.*` oder `links.create` zählen nicht). Die Pflicht greift vor jeder Admin-Funktion. Hintergrund: Wer `admin.roles.manage` oder `admin.permissions.manage` hat, kann sich selbst jedes weitere Recht geben und ist damit faktisch voller Admin.
- [E] Wiederherstellungscodes: 10 einmalige Codes bei der Einrichtung, nur gehasht gespeichert, einmal angezeigt. Admins können die 2FA eines Users zurücksetzen. Für den letzten Admin gibt es einen Befehl im Backend-Container.
- [A] TOTP mit 6 Stellen, 30 Sekunden, SHA-1 (damit gängige Apps funktionieren). TOTP-Geheimnisse werden verschlüsselt gespeichert (Schlüssel aus der Umgebung).

Erster Admin

- [E] Beim ersten Start, solange kein Admin existiert, schreibt das Backend einen besonderen Setup-Link ins Log. Über ihn erreicht man das Setup, dort wird der Admin-Account erstellt.
- [A] Der Link-Token ist zufällig (mindestens 256 Bit), nur gehasht gespeichert, einmalig und läuft nach 1 Stunde ab. Bei einem Neustart entsteht ein neuer Link, solange kein Admin existiert. Das Setup verlangt Passwort und die 2FA-Einrichtung inklusive Wiederherstellungscodes. Nach dem ersten Admin ist der Setup-Endpunkt dauerhaft tot.

Sitzungen und Tokens

- [A] Access-Token 10 Minuten. Refresh-Token 30 Tage mit Rotation bei "Eingeloggt bleiben", sonst endet die Sitzung nach 12 Stunden. Alle Werte einstellbar. Kurze Access-Tokens sorgen dafür, dass Sperren schneller greifen.
- [A] Zeiten in UTC speichern, in der UI in Europe/Berlin anzeigen.

## 4. Einladungslinks

- [E] Konten legt kein Admin direkt an. Admins und User mit der Berechtigung erzeugen Links. Ein Link wird bei der Nutzung verbraucht, und zwar beim Absenden des Formulars, nicht beim Öffnen (Mail- und Messenger-Vorschauen öffnen Links automatisch). Das Einlösen ist atomar (eine Datenbank-Transaktion mit Sperre), auch bei gleichzeitigen Anfragen.
- [E] Gültigkeit pro Link einstellbar: Minimum 1 Stunde, Standard 7 Tage, Maximum 1 Monat (fest 30 Tage). Links mit Berechtigungen oder Rolle: maximal 3 Tage, Standard dann ebenfalls 3 Tage. Ein Link ist nicht verlängerbar, nur deaktivierbar. Gespeichert wird ein absolutes `expires_at`, die Grenzen werden serverseitig geprüft und sind im Admin-Dashboard einstellbar.
- [E] Ein Link kann Berechtigungen oder eine Rolle mitgeben. Der Ersteller darf nur Rechte vergeben, die er selbst hat. Jedes Recht hat eine Einstellung "per Link vergebbar". Eine Rolle im Link ist nur erlaubt, wenn der Ersteller alle ihre Rechte hält und alle link-vergebbar sind. Das zählt als Link mit Berechtigungen (max. 3 Tage).
- [E] Beim Einlösen wird neu geprüft, ob der Ersteller noch nicht gebannt ist und alle Rechte (auch der Inhalt der Rolle) noch hat. Bei einem Verstoß wird der ganze Link abgelehnt.
- [E] Pro User gibt es ein Limit gleichzeitig aktiver Links (Zahlenrecht `links.max_active`). Der Platz wird frei, wenn der Link benutzt wird, abläuft oder deaktiviert wird.
- [E] Links werden nie gelöscht, nur deaktiviert. Gespeichert bleiben: Link-ID, Rolle, aufgelöste Rechte zum Zeitpunkt der Erstellung, was beim Einlösen vergeben wurde, Status mit Grund und `invited_by` am neuen User.
- [E] Wird der Ersteller gebannt, werden seine offenen Links deaktiviert. Konten, die vorher über seine Links entstanden sind, bleiben bestehen und werden nicht gebannt.
- [E] Ungültige, abgelaufene und benutzte Links zeigen dieselbe neutrale Fehlermeldung.
- [A] Der Token ist zufällig (mindestens 256 Bit) und wird nur gehasht gespeichert. Registrierungsadresse: `https://auth.anfall.net/invite/<token>`. Ist beim Absenden der Username oder die E-Mail vergeben, gibt es einen Formularfehler und der Link wird nicht verbraucht.
- [E] Sperre bei falschen Links: Wer mehr als 3-mal pro Stunde einen falschen Link öffnet, wird für einen Tag gesperrt. Gezählt werden nur Links, die es nie gab. Abgelaufene, benutzte oder deaktivierte Links zählen nicht (sonst sperrt sich ein Nutzer selbst). [A] Die Sperre gilt pro IP-Adresse (IPv6 pro /64) und nur für den Link-Endpunkt, nicht für den Login. Ein Admin kann sie aufheben. Die echte IP wird nur von Caddy übernommen (vertrauenswürdiger Proxy), sonst lässt sich die Sperre umgehen.
- [A] Weitere Limits: Login 5 Fehlversuche pro Konto in 15 Minuten, dann 15 Minuten Pause. Gleiches für falsche TOTP-Codes. Passwort-Reset-Anfragen 3 pro Stunde je E-Mail und IP.

## 5. Berechtigungen und Rollen

- [E] Berechtigungen sind typisiert: boolesch oder Zahl. Zahlenrechte haben pro Recht eine Richtung (höher = mehr Rechte oder niedriger = mehr Rechte). Es gilt der Wert, der mehr Rechte gibt. "Nicht gesetzt" ist ein eigener Zustand und nie gleich 0. "Unbegrenzt" ist ein eigener Zustand. [A] "Unbegrenzt" ist nur bei Richtung "höher = mehr" erlaubt. Eine Quelle, die einen Wert nicht setzt, beeinflusst den Vergleich nicht. Setzt keine Quelle einen Wert, hat der User dieses Recht nicht.
- [E] Rollen sind Live-Gruppen (wie bei Discord). Ändert sich eine Rolle, gilt das sofort für alle Mitglieder. User können mehrere Rollen haben und zusätzlich einzelne Rechte direkt bekommen.
- [E] Die effektiven Rechte werden immer aus allen Quellen (Rollen und direkte Rechte) neu berechnet, nie per Delta. Schreibe dafür Unit-Tests mit Konfliktfällen (mehrere Rollen, gegensätzliche Richtung, nicht gesetzt, unbegrenzt).
- [E] Im Admin-Dashboard können neue Berechtigungen und Rollen angelegt und verwaltet werden. Ein neues Recht hat nur Wirkung, wenn etwas es durchsetzt, nämlich als Anmeldevoraussetzung an einem Dienst (Client). Das Dashboard soll das deutlich machen.
- [A] Startliste der eingebauten Rechte (Joshua bestätigt sie):
  - `admin.users.view`, `admin.users.ban`, `admin.users.reset_2fa`
  - `admin.roles.manage`, `admin.permissions.manage`, `admin.services.manage`
  - `admin.links.view_all`, `admin.audit.view`, `admin.settings.manage`
  - `links.create` (boolesch), `links.max_active` (Zahl, höher = mehr, unbegrenzt möglich)
  - `service.<key>` (boolesch), wird automatisch mit jedem Dienst angelegt
- [A] Es gibt keinen Sonderstatus "Admin". Ein Admin ist ein User, der Verwaltungsrechte (`admin.*`) hat. Das Setup legt eine eingebaute Rolle `Admin` an, die alle `admin.*`-Rechte enthält (auch später neu hinzukommende) und dem ersten Account zugewiesen wird. Die Rolle `Admin` gibt von sich aus keinen Zugang zu Diensten. Dafür braucht es zusätzlich das jeweilige `service.<key>`-Recht.

## 6. OIDC-Provider und Dienste

- [E] Der Provider ist vollständig OIDC-konform: Discovery, Authorization, Token, JWKS, UserInfo, Refresh-Tokens. `sub` ist die User-UUID.
- [A] Authorization-Code-Flow mit PKCE. Signierschlüssel sind rotierbar (mehrere Schlüssel im JWKS).
- [E] Jeder Client verlangt eine Berechtigung. Fehlt sie, gibt der Provider keinen Code aus und zeigt eine verständliche Seite in der Sprache des Users. Die Berechtigung wird auch beim Token-Refresh geprüft, damit Entzug und Bann wirken.
- [U] Spring-Doku: OIDC ist im Authorization Server standardmäßig deaktiviert und muss aktiviert werden. Für den UserInfo-Endpoint wird ein `JwtDecoder`-Bean verlangt. Prüfe das in der aktuellen Doku.
- [E] Claims: Vaultwarden braucht `iss` und `sub`, `email`, `email_verified` und `preferred_username`. Scopes: `openid`, `profile`, `email`, `offline_access` (für Refresh-Tokens). Der Provider meldet `email_verified=true` nur für bestätigte Adressen.

Dienste (Clients) und Hosts: [A] laut Joshuas Infrastruktur-Notizen, vor Verwendung bestätigen:

| Dienst | Host | Stand |
| :- | :- | :- |
| Vaultwarden | `vault.anfall.net` | Callback `/identity/connect/oidc-signin`, nur Link, nie einbetten |
| Audiobookshelf | `audiobooks.anfall.net` | Mobil-Redirect `audiobookshelf://oauth` und `/auth/openid/mobile-redirect` [U] |
| Pelican Panel | `gs.anfall.net` | [O] erst später, Plugins vorher testen |

- [E] Jeder Dienst hat im Datenmodell einen Öffnungsmodus (`open_mode`: `new_tab` oder `embedded`). Zuerst wird nur weitergeleitet (neuer Tab). Der Umschalter pro Dienst muss später leicht möglich sein.
- [A] Dienste und Clients verwaltet das Admin-Dashboard: Name, Anzeigename, Redirect-URIs, benötigtes Recht, Öffnungsmodus, Health-URL.
- [U] Audiobookshelf: Rollenübernahme über OIDC-Claims und die Kriterien von "Match existing users by" sind nicht dokumentiert, ABS-Rollen pflegt Joshua vorerst in ABS selbst.
- [U] Sperren: Laut ABS-Doku wird das Refresh-Token nach dem Login gegen Audiobookshelf selbst geprüft, nicht gegen den Provider. Ein Bann im Hub verhindert dann neue Logins, beendet laufende ABS-Sitzungen aber nicht sofort. Bei Vaultwarden sagt die Doku nichts zu Sperren. Baue nicht auf sofortige Wirkung in den Diensten.
- Migration bestehender Konten: Vaultwarden verknüpft bestehende Konten beim ersten SSO-Login anhand der E-Mail (`SSO_SIGNUPS_MATCH_EMAIL` ist standardmäßig an), aber nur wenn `email_verified` gemeldet wird. Das Master-Passwort bleibt nötig. Joshua migriert Vaultwarden selbst (nur er nutzt es) und macht vorher ein Backup. Der normale Login bleibt an, bis SSO getestet ist.

## 7. Sperren und Löschen

- [E] Bann: blockiert nur Anmeldung und Nutzung der Dienste. Account und Daten bleiben, die E-Mail bleibt reserviert. Reversibel.
- [A] Ein Bann beendet Sitzungen, widerruft Refresh-Tokens und deaktiviert die offenen Links des Users. Niemand kann sich selbst oder den letzten Admin bannen.
- [E] Löschen wird zurückgestellt. Bei einer Löschanfrage löscht Joshua manuell, auch in den Diensten selbst. Bei einer späteren Löschung werden identifizierende Daten (E-Mail u. a.) entfernt, die UUID und verknüpfte Einträge bleiben. Der Username bleibt belegt.
- [O] Keine Lösch-Funktion bauen. Trenne aber im Datenmodell personenbezogene Daten klar von der UUID, damit eine Löschung oder Anonymisierung später möglich ist.

## 8. Hub-Oberfläche und Design

- [E] Links eine Sidebar mit den Diensten, die der User nutzen darf. Ein Klick leitet auf den Dienst weiter (neuer Tab).
- [E] Status: Jeder, der die Berechtigung für einen Dienst hat, sieht im Hub, ob dieser Dienst gerade aktiv ist. [A] Das Backend prüft die Health-URLs serverseitig alle 60 Sekunden. Der Status des Mail-Dienstes ist nur für Admins sichtbar. Es gibt nur eine Anzeige, keine Alarm-Mails.
- [A] Admin-Dashboard: User, Bann, Rollen, Rechte, alle Links, Dienste und Clients, Protokoll, Einstellungen (Link-Grenzen). Profilseite: Passwort, TOTP, Wiederherstellungscodes, eigene Links erstellen und deaktivieren.
- [E] Design: modern, freundlich und verspielt, Dark Mode zuerst. Es ist nicht der bisherige anfall.net-Look (kein Schwarz mit Rot und Cyan, keine Scanlines).
- [A] Etappe 6 beginnt mit 2 bis 3 Stilvorschlägen als statische Mockup-Seiten (Login und Hub-Startseite), Joshua wählt. Davor nur schlichtes, funktionales Styling mit CSS-Variablen. Light Mode kommt später, die Variablen sind so gebaut, dass er leicht ergänzt werden kann.
- [E] Die UI-Sprache ist pro Account einstellbar. Standard ist Englisch, als Alternative gibt es Deutsch. Alle Texte (UI, Fehlermeldungen, Mails) liegen in Sprachdateien (`en` und `de`), nichts steht fest im Code. Weitere Sprachen sollen später leicht ergänzbar sein.
- [A] Die Sprache wird als Feld am Account gespeichert (`en` oder `de`) und in der Profilseite umgestellt. Mails gehen in der Sprache des Accounts. Vor dem Login (Login, Registrierung, Setup, Passwort-Reset) gibt es einen Sprachumschalter, Standard Englisch, die Wahl wird nur im Browser gemerkt. Bei der Registrierung wird die gewählte Sprache als Account-Sprache gespeichert, die Bestätigungsmail kommt in dieser Sprache. Beim Setup des ersten Admins gilt dasselbe.
- [A] Keine externen Schriften oder CDNs (alles selbst hosten). Grundlegende Barrierefreiheit (Kontrast, Tastaturbedienung).
- [O] Wie sich die Hub-UI gegenüber der API authentifiziert (z. B. als öffentlicher OIDC-Client mit PKCE oder über das Backend), ist offen. Mache vor Etappe 3 einen Vorschlag mit Begründung in `docs/DECISIONS.md` und frage Joshua.

## 9. Protokoll (Audit-Log)

- [E] Jede Aktion, die etwas in der Datenbank ändert, und jedes Einloggen wird pro Account protokolliert.
- [E] IP-Adressen werden nach 90 Tagen gelöscht (Feld leeren, Eintrag bleibt).
- [A] Felder: Zeit, handelnder Account, betroffener Account, Aktion, Details (ohne Geheimnisse). Das Protokoll wird nur angehängt, nie geändert. Einsehbar mit `admin.audit.view`. Bei einer manuellen Löschung werden Einträge anonymisiert.
- [E] Nie ins Protokoll oder Log: Passwörter, Tokens, TOTP-Geheimnisse, Wiederherstellungscodes, Link-Token. Der Setup-Link steht bewusst im Log (siehe Abschnitt 3), danach ist er wertlos.

## 10. Sicherheitsbasis

Alles [A], Joshua bestätigt sie:

- HTTPS nur über Caddy, Sicherheits-Header (HSTS, CSP, `frame-ancestors 'none'` auf Login und Admin).
- Cookies `Secure`, `HttpOnly`, `SameSite=Lax`, host-only für `auth.anfall.net`. CSRF-Schutz bei Formularen. CORS nur für die nötigen Origins.
- Keine Tokens in `localStorage`, falls vermeidbar.
- Secrets nur über Umgebungsvariablen oder Docker-Secrets.
- Abhängigkeiten auf feste Versionen, bekannte Schwachstellen vor dem Release prüfen.

## 11. Etappen und Abnahmetests

Jede Etappe: Branch, Tests, PR, Zusammenfassung mit Annahmen und offenen Fragen, Freigabe abwarten.

1. **Grundgerüst:** Compose-Stack (Backend, UI, Datenbank, Mailpit im Profil `dev`), Healthchecks, Migrationen, Setup-Link, erster Admin mit Pflicht-2FA und Wiederherstellungscodes. Test: Stack startet frisch, Setup nur einmal möglich.
2. **Konten und Login:** Mail-Bestätigung, Login mit Username oder E-Mail, "Eingeloggt bleiben", Passwort-Reset, Passwort-Regeln (Argon2id, 15 Zeichen, Sperrliste), Limits, Protokoll-Grundlagen. Test: Regeln, Sperren und Reset als automatisierte Tests.
3. **OIDC-Provider:** Discovery, JWKS, Authorize, Token, UserInfo, Refresh, Rechteprüfung am Client, Bann widerruft Tokens. Test: Test-Client durchläuft den Flow, ohne Recht kein Code.
4. **Rollen, Rechte, Dashboard:** typisierte Rechte, Richtung, Live-Rollen, Neuberechnung der effektiven Rechte. Test: Konflikt-Unit-Tests aus Abschnitt 5.
5. **Einladungslinks:** alle Regeln aus Abschnitt 4. Test: Rennen beim gleichzeitigen Einlösen, Grenzen der Laufzeit, Prüfung des Erstellers beim Einlösen, Platzlimit, IP-Sperre.
6. **Hub-UI:** Stilvorschläge, Sidebar, Dienste, Statusanzeige, Profilseite.
7. **Absicherung und Auslieferung:** Header und CORS prüfen, `docs/DEPLOY.md`, `deploy/Caddyfile.snippet`, Backup-Hinweis, Überblick der offenen Punkte.

## 12. Offen und nicht verifiziert

- [O] Backup-Konzept der Datenbank.
- [O] E-Mail-Änderung durch User (nicht besprochen, nicht bauen).
- [O] Passkeys, Löschfunktion, Einbetten, Pelican-Anbindung, Dienste ohne OIDC.
- [O] Anmeldung der Hub-UI an der API (siehe Abschnitt 8).
- [O] Light Mode.
- [U] Audiobookshelf: Rollen aus Claims, Kriterien von "Match existing users by".
- [U] Wie schnell Sperren in Audiobookshelf und Vaultwarden wirken.
- [U] Pelican: Verhalten der Plugins (OIDC beim ersten Login, Limits pro User, Admin-Sicht).
- [U] Hetzner-Portsperre: laut Joshua nicht mehr aktiv, ausgehend Port 25 muss aber auf dem Server noch freigegeben werden. Zustellung an große Anbieter (Gmail, Outlook) ist in der Cloud-Sitzung nicht prüfbar.

## 13. Quellen

- Vaultwarden Wiki: Enabling SSO support using OpenID Connect (https://github.com/dani-garcia/vaultwarden/wiki/Enabling-SSO-support-using-OpenId-Connect)
- Audiobookshelf: OIDC Authentication (https://www.audiobookshelf.org/guides/oidc_authentication/)
- OWASP Password Storage Cheat Sheet (https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- NIST SP 800-63B-4, Passwords (https://pages.nist.gov/800-63-4/sp800-63b/passwords)
- Spring Authorization Server (https://docs.spring.io/spring-authorization-server/reference/overview.html)
- Have I Been Pwned: Pwned Passwords (https://haveibeenpwned.com/Passwords)
