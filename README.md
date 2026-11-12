# AuthenticationService

Zentrales User-Management und OIDC-Provider für die Dienste auf anfall.net. Das Backend (Spring Boot) läuft hier,
die Oberfläche liegt im Schwesterrepo `UserHub` (muss im Nachbarordner liegen). Die verbindliche Spezifikation
steht in `CLAUDE.md`, Entscheidungen in `docs/DECISIONS.md`, offene Fragen in `docs/OPEN-QUESTIONS.md`.

Stand: Etappe 2 (Konten, Login, Passwort-Reset, Mailversand). Der OIDC-Provider kommt mit Etappe 3.

## Setup in Kürze

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
- Rückläufer (Bounces) gehen an die Absenderadresse `MAIL_FROM` (`no-reply@anfall.net`). Dort muss ein Postfach oder eine
  Weiterleitung existieren, sonst gehen sie verloren (offene Frage in `docs/OPEN-QUESTIONS.md`).

## Betreiber-Befehle

2FA eines Kontos zurücksetzen, zum Beispiel für den letzten Admin, der sich ausgesperrt hat. Entfernt die 2FA und die
Wiederherstellungscodes und beendet alle Sitzungen:

```bash
docker compose exec auth-backend auth-admin reset-2fa joshua
```

Danach reicht beim Login das Passwort. Die 2FA wieder einzurichten ist ab Etappe 6 über die Profilseite möglich.

## Entwickeln und Testen

```bash
mvn test            # Unit- und Integrationstests, brauchen Docker (Test-PostgreSQL)
tests/e2e/run.sh    # Ende-zu-Ende im Browser, siehe Kommentar in der Datei
```

Zum Entwickeln mit Test-Mailserver statt dem echten: `MAIL_HOST=mailpit`, `MAIL_PORT=1025` in `.env` und
`docker compose --profile dev up -d --build`.

Die Sperrliste für Passwörter lädt der Docker-Build automatisch. Ohne Docker: `scripts/fetch-blocklist.sh data` und
`PASSWORD_BLOCKLIST_FILE=data/100k-most-used-passwords-NCSC.txt` setzen.
