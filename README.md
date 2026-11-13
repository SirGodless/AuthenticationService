# AuthenticationService

Zentrales User-Management und OIDC-Provider für die Dienste auf anfall.net. Das Backend (Spring Boot) läuft hier,
die Oberfläche liegt im Schwesterrepo `UserHub` (muss im Nachbarordner liegen). Die verbindliche Spezifikation
steht in `CLAUDE.md`.

**Stand:** Etappe 3. Konten, Login, Passwort-Reset, Mailversand, NIST-konforme Passwörter und der OIDC-Provider (Discovery, Authorize mit PKCE, Token, JWKS, UserInfo, Refresh mit Rechteprüfung am Dienst). Rollen, Rechte und Dashboard folgen mit Etappe 4.

## Ordnerübersicht

| Ordner / Datei | Was dort liegt |
| :- | :- |
| `src/main/java/net/anfall/auth/` | Der Backend-Code, ein Paket pro Fachthema (`account`, `login`, `password`, `twofactor`, `oidc`, `service`, `setup`, `audit`, `mail`, ...). Die Pakete sind in `docs/decisions/code-structure.md` erklärt. |
| `src/main/resources/db/migration/` | Datenbank-Migrationen (Flyway), eine pro Änderung. Schon gelaufene werden nie geändert. |
| `src/main/resources/mail/` | Mail-Texte, eine Datei pro Mail und Sprache (Englisch Standard, `_de` Deutsch). |
| `src/main/resources/application.yml` | Einstellungen, die aus Umgebungsvariablen kommen. |
| `src/test/java/net/anfall/auth/` | Tests, gleiche Paketstruktur wie der Code. `testsupport/` enthält die gemeinsamen Hilfen (Test-Datenbank, Test-Mailserver, Browser-Simulation). |
| `tests/e2e/` | Ende-zu-Ende-Test im echten Browser mit Test-Caddy (`run.sh`). |
| `scripts/` | Skripte: Sperrliste laden (`fetch-blocklist.sh`), Betreiber-Befehle im Container (`auth-admin`). |
| `docs/` | `DEPLOY.md` (Deployment), `decisions/` (Entscheidungen nach Thema, Inhaltsverzeichnis in `DECISIONS.md`), `NIST-800-63B-4.md`, `OPEN-QUESTIONS.md`, `THIRD-PARTY.md`. |
| `Dockerfile`, `docker-compose.yml`, `.env.example` | Image des Backends, der ganze Stack (Datenbank, Mailserver, Backend, UI), Vorlage für die Einstellungen. |
| `CLAUDE.md` | Die verbindliche Spezifikation für beide Repos. |

## Starten

Ausführlich in `docs/DEPLOY.md`. Der Mailserver hat unten einen eigenen Abschnitt, weil dafür auf dem Server und im DNS
etwas zu tun ist, bevor Mails ankommen.

```bash
cd /opt/containers
git clone https://github.com/SirGodless/AuthenticationService.git
git clone https://github.com/SirGodless/UserHub.git
cd AuthenticationService
cp .env.example .env        # Werte eintragen (siehe Kommentare in der Datei)
docker compose up -d --build
docker compose logs auth-backend | grep SETUP-LINK   # Link für den ersten Admin
```

## Testen

```bash
mvn test            # Unit- und Integrationstests, brauchen Docker (Test-PostgreSQL)
tests/e2e/run.sh    # Ende-zu-Ende im Browser, siehe Kommentar in der Datei (Node mit Playwright nötig)
```

Zum Entwickeln mit Test-Mailserver statt dem echten: `MAIL_HOST=mailpit`, `MAIL_PORT=1025` in `.env` und
`docker compose --profile dev up -d --build`.

Die Sperrliste für Passwörter lädt der Docker-Build automatisch. Ohne Docker: `scripts/fetch-blocklist.sh data` und
`PASSWORD_BLOCKLIST_FILE=data/100k-most-used-passwords-NCSC.txt` setzen.

## Mailserver

Der Stack enthält einen eigenen Mailserver (Container `auth-mail`, Postfix mit DKIM-Signatur). Er **versendet nur**,
empfängt nichts und hat **keine veröffentlichten Ports**. Das Backend gibt Mails intern an ihn ab, er liefert sie
selbst an die Postfächer der Empfänger aus. Damit das klappt und die Mails nicht im Spam landen, sind sechs Dinge nötig.
`anfall.net` steht hier für deine Domain, `mail.anfall.net` für den Hostnamen des Mailservers (`MAIL_HOSTNAME` in `.env`).

### 1. Ausgehend Port 25 auf dem Server freigeben

Mailserver liefern untereinander über **TCP Port 25** aus. Hetzner sperrt das auf neuen Servern, bei dir ist die Sperre
laut deiner Aussage aufgehoben. Zusätzlich muss **ausgehend TCP 25** in jeder Firewall erlaubt sein, die du selbst nutzt
(Hetzner Cloud Firewall mit Ausgangsregeln, `ufw`, `nftables`). Eingehend wird **nichts** freigegeben.

Falls du `ufw` mit gesperrtem Ausgang nutzt:

```bash
sudo ufw allow out 25/tcp
```

Prüfen, ob der Container nach draußen kommt (nach `docker compose up -d`):

```bash
docker compose exec mail nc -zv -w 5 gmail-smtp-in.l.google.com 25
```

Antwortet er mit `open` oder `succeeded`, ist Port 25 frei. Hängt er oder meldet `timed out`, blockiert noch etwas.

### 2. DNS: A-Eintrag für den Mailserver

`mail.anfall.net` als A-Eintrag auf die IPv4-Adresse des Servers. **Keinen AAAA-Eintrag** anlegen: Der Container
sendet nur über IPv4, ein AAAA-Eintrag mit anderer Absenderadresse würde bei SPF und rDNS stören.

### 3. Reverse-DNS (PTR) bei Hetzner

Die IP des Servers muss rückwärts auf `mail.anfall.net` zeigen. Bei Hetzner stellst du das in der Verwaltungsoberfläche
beim Server ein (Cloud Console: Server, Networking, Reverse DNS, bei dedizierten Servern in der Robot-Oberfläche; die
Menünamen können abweichen). Der Eintrag muss **exakt** `MAIL_HOSTNAME` entsprechen. Ohne passenden PTR lehnen viele
Anbieter die Mails ab oder sortieren sie in den Spam.

### 4. SPF

TXT-Eintrag auf `anfall.net`:

```
v=spf1 ip4:DEINE_SERVER_IP ~all
```

Hast du für `anfall.net` schon einen SPF-Eintrag (z. B. für einen anderen Mailanbieter), **ergänze** ihn um
`ip4:DEINE_SERVER_IP`, statt einen zweiten anzulegen. Zwei SPF-Einträge machen beide ungültig.
Wenn alles läuft, kannst du `~all` zu `-all` verschärfen.

### 5. DKIM

Beim ersten Start erzeugt der Container einen Schlüssel (liegt im Volume `mail-dkim`, bleibt bei Neustarts erhalten).
Den öffentlichen Teil holst du so:

```bash
docker compose exec mail cat /var/lib/rspamd/dkim/anfall.net.mail.txt
```

Die Ausgabe sieht so aus: `mail._domainkey IN TXT ( "v=DKIM1; k=rsa; " "p=MIIB..." )`. Lege daraus einen TXT-Eintrag
an: **Name** `mail._domainkey.anfall.net`, **Wert** alle Teile in den Anführungszeichen hintereinander, also
`v=DKIM1; k=rsa; p=MIIB...` (ohne Anführungszeichen und Klammern). Manche DNS-Oberflächen wollen den Wert in einem
Stück, manche in 255-Zeichen-Teilen, dann schneidest du ihn dort passend.

Löschst du das Volume `mail-dkim`, entsteht ein neuer Schlüssel und der DNS-Eintrag muss neu gesetzt werden.

### 6. DMARC

TXT-Eintrag auf `_dmarc.anfall.net`:

```
v=DMARC1; p=none
```

`p=none` beobachtet nur. Wenn Testmails sauber durchlaufen, kannst du auf `p=quarantine` erhöhen.

### Testen

Wie gut ein Testversand bewertet wird, zeigen Dienste wie mail-tester.com (externer Dienst, du schickst nur eine
Testmail an deren Adresse, es gibt keine Zugangsdaten weiterzugeben). Eine Mail auslösen kannst du mit
"Passwort vergessen" für ein bestätigtes Konto. Den Zustand der Warteschlange siehst du hier:

```bash
docker compose exec mail postqueue -p
docker compose logs mail | grep status=
```

`status=sent` heißt zugestellt, `status=deferred` mit Begründung heißt: Der Empfänger nimmt die Mail (noch) nicht an,
Postfix versucht es weiter.

### Ehrlich zu erwarten

- Eine **neue Server-IP hat keinen Ruf**. Auch bei korrektem SPF, DKIM, DMARC und PTR landen die ersten Mails bei Gmail
  oder Outlook mitunter im Spam oder werden vorübergehend abgelehnt. Das legt sich meist nach einigen Tagen mit
  normalem Verkehr, ist aber nicht vorhersehbar.
- Mails werden **ohne Wiederholung durch die Anwendung** versendet: Schlägt die Übergabe an den Mailserver fehl,
  steht ein Fehler im Backend-Log und der User fordert die Mail neu an. Ist die Übergabe geklappt, wiederholt Postfix die
  Auslieferung selbst über mehrere Tage.
- Es gibt **keinen Posteingang**. Rückläufer (Bounces) gehen an die Absenderadresse `MAIL_FROM` und werden dort vom
  Mailserver verworfen. Wenn eine Mail nicht zugestellt werden kann, siehst du es nur im Log:
  `docker compose logs mail | grep status=bounced`.

## Dienste anschließen (Vaultwarden, Audiobookshelf)

Ein Dienst ist ein OIDC-Client. Bis das Dashboard (Etappe 4) die Verwaltung übernimmt, legst du ihn mit einem Befehl an. Beispiel mit der Rücksprungadresse aus der Vaultwarden-Anleitung:

```bash
docker compose exec auth-backend auth-admin register-service vault Vaultwarden https://vault.anfall.net/identity/connect/oidc-signin
```

Der Befehl zeigt **einmal** die Client-ID (`vault`) und das Client-Secret an. Trage beides im Dienst ein, als Aussteller (Authority, Issuer) gilt `https://auth.anfall.net`. Danach gibst du einem Konto den Zugang:

```bash
docker compose exec auth-backend auth-admin grant-service joshua vault
```

Ohne dieses Recht gibt der Provider keinen Code aus, der User sieht die Seite "Kein Zugang". Das Recht wird bei jedem Token-Refresh neu geprüft. Die genauen Einstellungen der Dienste (Scopes `openid profile email offline_access`, PKCE einschalten, Rücksprungadressen) stehen in deren Anleitungen. Das Zusammenspiel mit den echten Diensten testest du nach dem Deployment (in der Entwicklung nicht prüfbar).

## Betreiber-Befehle

2FA eines Kontos zurücksetzen, zum Beispiel für den letzten Admin, der sich ausgesperrt hat. Entfernt die 2FA und die
Wiederherstellungscodes und beendet alle Sitzungen:

```bash
docker compose exec auth-backend auth-admin reset-2fa joshua
```

Danach reicht beim Login das Passwort. Die 2FA wieder einzurichten ist ab Etappe 4 möglich (API und Seite, siehe
`docs/OPEN-QUESTIONS.md`).

Neuen Signierschlüssel für die Tokens erzeugen (der alte bleibt zwei Tage zum Prüfen im JWKS):

```bash
docker compose exec auth-backend auth-admin rotate-signing-key
```

Alle Befehle zeigt `auth-admin` ohne Parameter an.
