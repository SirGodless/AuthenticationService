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

docker network create caddy_net >/dev/null 2>&1 || true
# Die IP aus dem Test-Caddy soll geglaubt werden: Subnetz des Netzwerks eintragen
export TRUSTED_PROXIES=$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}}{{end}}' caddy_net)
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d ${BUILD_FLAG:---build} --wait
trap 'docker rm -f caddy-test >/dev/null 2>&1 || true' EXIT

docker rm -f caddy-test >/dev/null 2>&1 || true
docker run -d --name caddy-test --network caddy_net \
  -v "$PWD/tests/e2e/Caddyfile.test:/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null
sleep 4
IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "caddy_net").IPAddress}}' caddy-test)

TOKEN=$(docker logs auth-backend 2>&1 | grep -o 'SETUP-LINK.*' | tail -1 | grep -o '/setup/[A-Za-z0-9_-]*' | cut -d/ -f3)
[ -n "$TOKEN" ] || { echo "Kein Setup-Link im Log gefunden"; exit 1; }

# Mailpit hat keinen veröffentlichten Port: Wir fragen es aus dem Backend-Container (gleiches Netzwerk)
node tests/e2e/setup-flow.js "$IP" "$TOKEN" "docker exec auth-backend wget -qO- http://mailpit:8025" "docker exec auth-backend"
