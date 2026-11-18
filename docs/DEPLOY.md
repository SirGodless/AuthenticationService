# Deployment

Alles läuft in einem Docker-Compose-Stack hinter deinem bestehenden Caddy. Die Schritte sind in der Reihenfolge, in der du sie machst. Ich ändere deinen Caddy nicht, du trägst den Block selbst ein.

Voraussetzungen auf dem Server: Docker mit Compose, der bestehende Caddy, das Docker-Netzwerk `caddy_net`, DNS-Einträge für `auth.anfall.net`, `anfall.net` und `www.anfall.net` (A-Einträge auf den Server) und, für die Mails, die Einrichtung aus der `README.md` (Abschnitt "Mailserver": Port 25 ausgehend, rDNS, SPF, DKIM, DMARC).

## 1. Repos klonen

Beide Repos in denselben Ordner, nebeneinander (die UI wird aus `../UserHub` gebaut):

```bash
cd /opt/containers
git clone https://github.com/SirGodless/AuthenticationService.git
git clone https://github.com/SirGodless/UserHub.git
cd AuthenticationService
```

## 2. Einstellungen eintragen

```bash
cp .env.example .env
```

Das Adressnetz von `caddy_net` gehört in `TRUSTED_PROXIES`. Nur von dort glaubt das Backend den Angaben von Caddy zur echten IP der Besucher (sonst ließen sich IP-Sperren umgehen). Prüfen mit:

```bash
docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' caddy_net
```

Vier Werte erzeugst du so, jeder muss ein anderer sein:

```bash
openssl rand -base64 24   # DB_PASSWORD
openssl rand -base64 32   # TOTP_ENCRYPTION_KEY
openssl rand -base64 32   # PASSWORD_PEPPER_KEY
openssl rand -base64 32   # OIDC_KEY_ENCRYPTION_KEY
```

Die drei Schlüssel nie mehr ändern. `PASSWORD_PEPPER_KEY` bewahrst du **getrennt** vom Backup auf (Passwortmanager), sonst schützt er nichts. Geht er verloren, passt kein bestehendes Passwort mehr. Mit einem neuen Pepper müssten alle per Passwort-Reset ein neues setzen.

## 3. Ordner für Backups anlegen

Die Backups landen auf diesem Server (so entschieden). Der Backup-Container läuft ohne Root-Rechte als Benutzer 70, dem Ordner muss er gehören:

```bash
mkdir -p backups
sudo chown 70:70 backups
```

Wenn du den Ordner woanders willst, `BACKUP_DIR` in `.env` ändern.

## 4. Starten

```bash
docker compose up -d --build
docker compose ps
```

`auth-db`, `auth-mail`, `auth-backend`, `userhub-ui` und `auth-backup` sollen `healthy` zeigen. `auth-backup` macht beim ersten Start sofort ein Backup, deshalb ist er nach kurzer Zeit gesund. Der erste Build dauert ein paar Minuten.

## 5. Caddy

Den Inhalt von `deploy/Caddyfile.snippet` in deine Caddyfile übernehmen (er enthält zwei Blöcke), dann:

```bash
caddy validate --config /etc/caddy/Caddyfile
caddy reload --config /etc/caddy/Caddyfile
```

Der Block verteilt auf `auth.anfall.net` die Pfade `/oauth2/*`, `/.well-known/*`, `/connect/*`, `/userinfo` und `/api/*` an `auth-backend:8080` und alles andere an `userhub-ui:8080`, setzt HSTS und leitet `anfall.net` und `www.anfall.net` dauerhaft auf `https://auth.anfall.net` um. Der interne Port 8081 des Backends (Health) wird nicht weitergegeben. **Prüfen**, sobald Caddy läuft:

```bash
curl -s https://auth.anfall.net/.well-known/openid-configuration | head -c 300    # "issuer": "https://auth.anfall.net"
curl -sI https://auth.anfall.net/login | grep -i -E "strict-transport|content-security|x-frame"
```

Die Adresse `https://auth.anfall.net` ist der Issuer und darf sich nie ändern (sie steht in allen Diensten).

## 6. Ersten Admin einrichten

Beim ersten Start schreibt das Backend einen Setup-Link ins Log:

```bash
docker compose logs auth-backend | grep SETUP-LINK
```

Den Link im Browser öffnen (gilt eine Stunde, einmal). Dort Account, Passwort und 2FA einrichten und die Wiederherstellungscodes sicher speichern. Ist der Link abgelaufen: `docker compose restart auth-backend`, solange kein Admin existiert, entsteht ein neuer. Danach ist das Setup dauerhaft gesperrt.

## 7. Erste Schritte im Dashboard

Unter `https://auth.anfall.net/admin`:

1. **Users:** Dir selbst die direkten Rechte `links.create` und `links.max_active` (z. B. 5) geben, damit du Einladungslinks erzeugen kannst (die Rolle Admin enthält sie nicht).
2. **Settings:** Gültigkeit der Einladungslinks einstellen, falls die Standardwerte (7 Tage, mit Rechten 3 Tage, Maximum 30) nicht passen.
3. **Services:** Dienst anlegen (Schlüssel, Name, Rücksprung-Adressen). Das **Client-Geheimnis** wird nur einmal angezeigt: Gleich im Dienst eintragen. Danach Beschreibung, Adresse zum Öffnen, Adresse für die Statusprüfung (z. B. `https://media.anfall.net/health`) und die Akzentfarbe eintragen. Wie sich ein Dienst anbindet, steht in `docs/INTEGRATION.md`. (Per Befehl geht es auch: `docker compose exec auth-backend auth-admin register-service <key> <Name> <redirect-uri>`.)
4. **Users oder Roles:** Das Recht `service.<key>` einem Nutzer oder einer Rolle geben (oder `auth-admin grant-service <username> <key>`). Ohne das Recht sieht der Nutzer den Dienst nicht und bekommt keinen Code.
5. **Profil** (`/account`): Sprache und Darstellung (hell, dunkel, wie das Gerät) einstellen.

### Einmal anmelden, überall drin

Der Hub und jeder Dienst teilen **eine** Anmeldung beim Provider (`auth.anfall.net`). Ist man dort angemeldet, bekommt jeder Dienst, für den man das Recht hat, beim Öffnen sofort einen Code, ohne neue Eingabe. Das gilt auch, wenn man einen Dienst direkt aufruft statt über den Hub: Der Dienst schickt zum Provider, ist man angemeldet, geht es sofort zurück (getestet in `SingleSignOnTest`). Ohne Anmeldung landet man auf der Login-Seite und danach wieder im Dienst. Voraussetzung ist, dass der Dienst per OIDC angebunden ist. Wie sich die echten Dienste dabei verhalten (Vaultwarden, Audiobookshelf, Pelican), testest du nach dem Deployment, das ging in der Entwicklung nicht.

## 8. Mailserver einrichten

Siehe `README.md`, Abschnitt "Mailserver". Ohne die dortigen DNS-Einträge funktionieren Login und Passwort-Reset, aber die Mails kommen bei vielen Anbietern nicht an.

## 9. Backup

Der Container `auth-backup` macht **jeden Tag um 0 Uhr** (Zeitzone `BACKUP_TZ`, Standard Europe/Berlin) ein Backup und **am Monatsersten** zusätzlich ein monatliches. Tägliche Backups werden nach 7 Tagen gelöscht, monatliche nach einem Jahr. Was im Ordner `BACKUP_DIR` liegt:

```
backups/daily/auth-2026-11-18.dump     Datenbank (pg_dump, Format custom)
backups/daily/keys-2026-11-18.env      TOTP_ENCRYPTION_KEY und OIDC_KEY_ENCRYPTION_KEY
backups/daily/dkim-2026-11-18.tgz      DKIM-Schlüssel des Mailservers
backups/monthly/...                    dieselben Dateien, jeweils vom Ersten des Monats
```

Der Pepper (`PASSWORD_PEPPER_KEY`) steht bewusst **nicht** im Backup, die Dateien sind nur für den Besitzer lesbar. Wer an den Ordner kommt, bekommt Datenbank und die beiden Schlüssel zusammen: Behandle ihn wie die `.env`. Das Ziel ist ein Ordner auf diesem Server (so entschieden).

**Prüfen:** `docker compose ps` zeigt `auth-backup` als `healthy`, wenn das letzte Backup nicht älter als 36 Stunden ist. `docker compose logs auth-backup` zeigt jedes Backup und die gelöschten Dateien. Ein Test des Ablaufs (400 Tage nachgespielt, Aufbewahrung und Wiederherstellung) liegt in `backup/test.sh`.

**Wiederherstellen** (ersetzt die Daten der Datenbank):

```bash
docker compose stop auth-backend
docker compose exec auth-backup restore.sh /backups/daily/auth-2026-11-18.dump
docker compose start auth-backend
```

Bei einem neuen Server: `.env` mit denselben Schlüsseln wie vorher (die beiden stehen auch in `keys-*.env`, den Pepper holst du aus dem Passwortmanager), Stack starten, Backend stoppen, wie oben zurückspielen, DKIM-Schlüssel zurück ins Volume (`docker run --rm -v authservice_mail-dkim:/d -v $PWD/backups/daily:/b alpine tar -xzf /b/dkim-2026-11-18.tgz -C /d`) und Backend starten.

## 10. Aktualisieren

```bash
cd /opt/containers/AuthenticationService && git pull && cd ../UserHub && git pull && cd ../AuthenticationService
docker compose up -d --build
```

Datenbank-Migrationen laufen beim Start von selbst. Schon gelaufene Migrationen werden nie geändert (Flyway verweigert sonst den Start). Mach vor einem Update, wenn du magst, ein Backup von Hand: `docker compose exec auth-backup backup.sh`.

Die Basis-Images sind per Digest festgenagelt (Stand 18.11.2026). Zum Aktualisieren: neuen Digest holen (`docker pull <image:tag>`, dann `docker inspect --format '{{index .RepoDigests 0}}' <image:tag>`) und in `Dockerfile`, `backup/Dockerfile`, `docker-compose.yml` und `../UserHub/Dockerfile` eintragen.

## 11. Betreiber-Befehle

```bash
docker compose exec auth-backend auth-admin reset-2fa <username>      # 2FA zurücksetzen, z. B. für den letzten Admin
docker compose exec auth-backend auth-admin rotate-signing-key        # neuer Signierschlüssel für die Tokens
docker compose exec auth-backend auth-admin register-service ...      # Dienst anlegen
docker compose exec auth-backend auth-admin grant-service <username> <key>
```

## 12. Wenn etwas nicht geht

| Beobachtung | Wahrscheinlich |
| :- | :- |
| `auth-backup` ist `unhealthy` | `BACKUP_DIR` gehört nicht dem Benutzer 70 (`sudo chown 70:70 backups`), siehe `docker compose logs auth-backup` |
| Login-Seite lädt, Anmelden gibt "Server nicht erreichbar" | Caddy leitet `/api/*` nicht an `auth-backend:8080` |
| IP-Sperren greifen alle Besucher | `TRUSTED_PROXIES` passt nicht zu `caddy_net`, alle Anfragen kommen scheinbar von Caddy |
| Dienst zeigt im Hub immer "unbekannt" | Keine Adresse für die Statusprüfung eingetragen (Dashboard, Reiter Services) |
| Dienst zeigt "offline", läuft aber | Die Statusadresse antwortet nicht mit 2xx oder 3xx innerhalb von 5 Sekunden (vom Backend-Container aus erreichbar?) |
| Mails kommen nicht an | `README.md`, Abschnitt "Mailserver", und `docker compose logs mail` |

## Entwicklung mit Test-Mailserver

```bash
docker compose --profile dev up -d --build
```

Startet zusätzlich Mailpit (nur im internen Netzwerk, ohne veröffentlichte Ports).

## Tests

```bash
mvn test                  # Unit- und Integrationstests (brauchen Docker für das Test-PostgreSQL)
backup/test.sh            # Backup: Zeitplan, Aufbewahrung, Wiederherstellung (braucht Docker)
tests/e2e/run.sh          # Ende-zu-Ende im Browser mit Test-Caddy aus deploy/Caddyfile.snippet (Node mit Playwright nötig)
node ../UserHub/tests/check-i18n.js    # Sprachdateien der UI
```

Achtung: `tests/e2e/run.sh` setzt den Stack mit `down -v` zurück und löscht dessen Datenbank.
