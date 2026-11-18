#!/bin/sh
# Spielt einen Dump zurück: restore.sh /backups/daily/auth-JJJJ-MM-TT.dump
# VORHER das Backend stoppen (docker compose stop auth-backend), sonst arbeitet es mit halb zurückgespielten Daten.
# Bestehende Tabellen werden ersetzt.
set -eu
[ $# -eq 1 ] && [ -f "$1" ] || { echo "Aufruf: restore.sh <Dump-Datei>" >&2; exit 2; }
pg_restore --clean --if-exists --no-owner --exit-on-error -d "$PGDATABASE" "$1"
echo "Zurückgespielt: $1"
