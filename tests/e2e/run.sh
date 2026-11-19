#!/usr/bin/env bash
# Ende-zu-Ende-Test: frischer Stack, Setup-Link aus dem Log, Durchlauf im Browser (Setup, Login mit 2FA,
# Passwort vergessen mit Mail aus Mailpit, Abmelden). Ein Test-Caddy verteilt die Pfade wie der echte Caddy.
#
# Voraussetzungen: Docker, Node mit installiertem Playwright (npm i playwright). Eine Env-Datei mit den Werten
# aus .env.example in ENV_FILE (Standard .env). Für den Test wird MAIL_HOST=mailpit gesetzt, damit die Mails
# lesbar sind. Das Netzwerk caddy_net wird bei Bedarf angelegt.
# Achtung: "docker compose down -v" löscht die Datenbank dieses Stacks.
set -euo pipefail
cd "$(dirname "$0")/../.."
ENV_FILE="${ENV_FILE:-.env}"
# Im Test heißt der Dienst auth.test, damit die Links in den Mails zum Test-Caddy passen
export MAIL_HOST=mailpit MAIL_PORT=1025 PUBLIC_BASE_URL=https://auth.test
COMPOSE=(docker compose --env-file "$ENV_FILE" --profile dev)
BUILD_FLAG="${NO_BUILD:+--no-build --pull never}"

# Backups des Test-Stacks in einen Wegwerf-Ordner (der Backup-Container läuft als Benutzer 70)
export BACKUP_DIR=$(mktemp -d); chmod 777 "$BACKUP_DIR"
docker network create caddy_net >/dev/null 2>&1 || true
# Die IP aus dem Test-Caddy soll geglaubt werden: Subnetz des Netzwerks eintragen
export TRUSTED_PROXIES=$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' caddy_net)
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d ${BUILD_FLAG:---build} --wait
trap 'docker rm -f caddy-test >/dev/null 2>&1 || true' EXIT

# Der Test-Caddy nutzt den echten Block aus deploy/Caddyfile.snippet, nur mit Testadresse und eigenem Zertifikat
# (HTTPS ist nötig, weil die Cookies "Secure" sind).
CADDYFILE=$(mktemp)
{ printf '{\n\tlocal_certs\n\tskip_install_trust\n}\n'
  sed -n '/^anfall\.net {/,/^}/p' deploy/Caddyfile.snippet | sed 's/^anfall\.net {/auth.test {\n\ttls internal/'; } > "$CADDYFILE"
docker rm -f caddy-test >/dev/null 2>&1 || true
docker run -d --name caddy-test --network caddy_net \
  -v "$CADDYFILE:/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null
sleep 4
IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "caddy_net").IPAddress}}' caddy-test)

# Der Backup-Container macht beim Start sofort ein Backup, falls es für heute noch keines gibt
for i in $(seq 1 30); do ls "$BACKUP_DIR"/daily/auth-*.dump >/dev/null 2>&1 && break; sleep 1; done
ls "$BACKUP_DIR"/daily/auth-*.dump >/dev/null 2>&1 && echo "ok: Backup: Dump entstanden" || { echo "FAIL: Backup: kein Dump"; exit 1; }
ls "$BACKUP_DIR"/daily/keys-*.env >/dev/null 2>&1 && echo "ok: Backup: Schlüsseldatei entstanden" || { echo "FAIL: Backup: keine Schlüsseldatei"; exit 1; }
ls "$BACKUP_DIR"/daily/dkim-*.tgz >/dev/null 2>&1 && echo "ok: Backup: DKIM-Schlüssel gesichert" || echo "FAIL: Backup: DKIM-Schlüssel nicht gesichert"
[ "$(docker inspect -f '{{.State.Health.Status}}' auth-backup)" = healthy ] && echo "ok: Backup: Container gesund" || echo "FAIL: Backup: Container nicht gesund"
TOKEN=$(docker logs auth-backend 2>&1 | grep -o 'SETUP-LINK.*' | tail -1 | grep -o '/setup/[A-Za-z0-9_-]*' | cut -d/ -f3)
[ -n "$TOKEN" ] || { echo "Kein Setup-Link im Log gefunden"; exit 1; }

# Mailpit hat keinen veröffentlichten Port: Wir fragen es aus dem Backend-Container (gleiches Netzwerk)
node tests/e2e/setup-flow.js "$IP" "$TOKEN" "docker exec auth-backend wget -qO- http://mailpit:8025" "docker exec auth-backend"
