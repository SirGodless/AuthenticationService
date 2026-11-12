# Deployment (Entwurf, wird in Etappe 7 vervollständigt)

Voraussetzungen auf dem Server: Docker mit Compose, der bestehende Caddy, das Docker-Netzwerk `caddy_net`.

## 1. Repos klonen

Beide Repos in denselben Ordner, nebeneinander:

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

Dann `.env` bearbeiten. Zwei Werte erzeugst du so:

```bash
openssl rand -base64 24   # für DB_PASSWORD
openssl rand -base64 32   # für TOTP_ENCRYPTION_KEY
```

Den `TOTP_ENCRYPTION_KEY` nach dem ersten Setup nie mehr ändern und im Backup mitführen.

## 3. Starten

```bash
docker compose up -d --build
docker compose ps
```

Alle drei Container (`auth-db`, `auth-backend`, `userhub-ui`) sollen `healthy` zeigen. Der erste Build dauert ein paar Minuten.

## 4. Caddy

Der Block für `deploy/Caddyfile.snippet` kommt in Etappe 7. Bis dahin ist der Stack nur intern im Docker-Netzwerk erreichbar. Vorgesehene Verteilung auf `auth.anfall.net`: `/oauth2/*`, `/.well-known/*`, `/connect/*`, `/userinfo` und `/api/*` an `auth-backend:8080`, alles andere an `userhub-ui:8080`.

## 5. Ersten Admin einrichten

Beim ersten Start schreibt das Backend einen Setup-Link ins Log:

```bash
docker compose logs auth-backend | grep SETUP-LINK
```

Den Link im Browser öffnen (gilt eine Stunde und nur einmal). Dort Account, Passwort und 2FA einrichten und die Wiederherstellungscodes sicher speichern. Wenn der Link abgelaufen ist, `docker compose restart auth-backend`: Solange kein Admin existiert, entsteht ein neuer Link. Nach dem ersten Admin ist das Setup dauerhaft gesperrt.

## 6. Backup (Platzhalter)

Dump der Datenbank in eine Datei:

```bash
docker compose exec -T auth-db pg_dump -U auth auth > auth-backup.sql
```

Wiederherstellen in eine leere Datenbank:

```bash
docker compose exec -T auth-db psql -U auth auth < auth-backup.sql
```

Wie, wie oft und wohin gesichert wird, ist offen (siehe `OPEN-QUESTIONS.md`).

## Entwicklung mit Test-Mailserver

```bash
docker compose --profile dev up -d --build
```

Startet zusätzlich Mailpit (nur im internen Netzwerk, ohne veröffentlichte Ports).

## Tests

Unit- und Integrationstests (brauchen Docker für das Test-PostgreSQL):

```bash
mvn test
```

Ende-zu-Ende-Test mit Browser: `tests/e2e/run.sh` (siehe Kommentar in der Datei, braucht Node mit Playwright). Achtung: Er setzt den Stack mit `down -v` zurück und löscht dessen Datenbank.
