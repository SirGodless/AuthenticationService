#!/usr/bin/env bash
# Ende-zu-Ende-Test: frischer Stack, Setup-Link aus dem Log, Durchlauf im Browser.
# Ein kleiner Caddy-Testcontainer verteilt die Pfade wie der echte Caddy (nur HTTP, nur für diesen Test).
#
# Voraussetzungen: Docker, Node mit installiertem Playwright (npm i playwright), eine .env im Repo-Ordner
# oder ENV_FILE=/pfad/zur/datei. Das Netzwerk caddy_net wird bei Bedarf angelegt.
# Achtung: "docker compose down -v" löscht die Datenbank dieses Stacks.
set -euo pipefail
cd "$(dirname "$0")/../.."
ENV_FILE="${ENV_FILE:-.env}"
COMPOSE=(docker compose --env-file "$ENV_FILE")
BUILD_FLAG="${NO_BUILD:+--no-build}"

docker network create caddy_net >/dev/null 2>&1 || true
"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d ${BUILD_FLAG:---build} --wait
trap 'docker rm -f caddy-test >/dev/null 2>&1 || true' EXIT

docker rm -f caddy-test >/dev/null 2>&1 || true
docker run -d --name caddy-test --network caddy_net \
  -v "$PWD/tests/e2e/Caddyfile.test:/etc/caddy/Caddyfile:ro" caddy:2-alpine >/dev/null
sleep 3
IP=$(docker inspect -f '{{(index .NetworkSettings.Networks "caddy_net").IPAddress}}' caddy-test)

# Der Setup-Link steht im Log des Backends
TOKEN=$(docker logs auth-backend 2>&1 | grep -o 'SETUP-LINK.*' | tail -1 | grep -o '/setup/[A-Za-z0-9_-]*' | cut -d/ -f3)
[ -n "$TOKEN" ] || { echo "Kein Setup-Link im Log gefunden"; exit 1; }

node tests/e2e/setup-flow.js "http://$IP" "$TOKEN"
