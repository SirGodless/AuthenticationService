#!/usr/bin/env bash
# Prüft Aufbewahrung und Wiederherstellung des Backups mit einer Wegwerf-Datenbank (braucht Docker, kein Compose-Stack).
# Spielt 400 Tage nach: täglich ein Backup, am Ersten zusätzlich ein monatliches. Erwartet am Ende genau 7 tägliche Backups
# (die letzten 6 Tage plus heute, ältere nach 7 Tagen gelöscht) und 12 monatliche, und dass sich ein Dump zurückspielen lässt.
set -euo pipefail
cd "$(dirname "$0")"
NET=backup-test-net; DB=backup-test-db; OUT=$(mktemp -d)
cleanup() { docker rm -f "$DB" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; docker run --rm -v "$OUT":/o alpine rm -rf /o/daily /o/monthly /o/.last_ok >/dev/null 2>&1 || true; rmdir "$OUT" 2>/dev/null || true; }
trap cleanup EXIT
docker build -q -t auth-backup:test . >/dev/null
docker network create "$NET" >/dev/null
docker run -d --name "$DB" --network "$NET" -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=auth -e POSTGRES_USER=auth postgres:17.11-alpine >/dev/null
for i in $(seq 1 30); do docker exec "$DB" pg_isready -U auth -d auth >/dev/null 2>&1 && break; sleep 1; done
sleep 2
docker exec "$DB" psql -U auth -d auth -qc "CREATE TABLE t (n int); INSERT INTO t VALUES (1), (2), (3);"
chmod 777 "$OUT"
run() { docker run --rm --network "$NET" -v "$OUT":/backups -e PGHOST="$DB" -e PGUSER=auth -e PGDATABASE=auth -e PGPASSWORD=pw -e TZ=Europe/Berlin \
  -e TOTP_ENCRYPTION_KEY=totp -e OIDC_KEY_ENCRYPTION_KEY=oidc -e BACKUP_TODAY="$1" --entrypoint backup.sh auth-backup:test; }
start=$(date -d "2025-10-01" +%s)
for i in $(seq 0 399); do
  d=$(date -d "@$((start + i * 86400))" +%F)
  run "$d" >/dev/null
done
last=$(date -d "@$((start + 399 * 86400))" +%F)
daily=$(ls "$OUT/daily" | grep -c '^auth-.*\.dump$'); monthly=$(ls "$OUT/monthly" | grep -c '^auth-.*\.dump$')
echo "letzter Tag: $last, tägliche Dumps: $daily, monatliche Dumps: $monthly"
fail=0
[ "$daily" -eq 7 ] || { echo "FEHLER: erwartet 7 tägliche Dumps"; fail=1; }
[ "$monthly" -eq 12 ] || { echo "FEHLER: erwartet 12 monatliche Dumps"; fail=1; }
oldest=$(ls "$OUT/daily" | grep '^auth-.*\.dump$' | sort | head -n1); echo "ältester täglicher: $oldest"
[ "$oldest" = "auth-$(date -d "@$((start + 393 * 86400))" +%F).dump" ] || { echo "FEHLER: ältester täglicher Dump ist nicht der von vor 6 Tagen"; fail=1; }
[ -f "$OUT/daily/keys-$last.env" ] && grep -q TOTP_ENCRYPTION_KEY=totp "$OUT/daily/keys-$last.env" || { echo "FEHLER: Schlüsseldatei fehlt"; fail=1; }
[ "$(stat -c %a "$OUT/daily/auth-$last.dump")" = "600" ] || { echo "FEHLER: Dump nicht nur für den Besitzer lesbar"; fail=1; }
# Wiederherstellung: Daten ändern, Dump zurückspielen, Daten wieder da
docker exec "$DB" psql -U auth -d auth -qc "DELETE FROM t; INSERT INTO t VALUES (99);"
docker run --rm --network "$NET" -v "$OUT":/backups -e PGHOST="$DB" -e PGUSER=auth -e PGDATABASE=auth -e PGPASSWORD=pw --entrypoint restore.sh auth-backup:test "/backups/daily/auth-$last.dump" >/dev/null
rows=$(docker exec "$DB" psql -U auth -d auth -Atc "SELECT string_agg(n::text, ',' ORDER BY n) FROM t")
[ "$rows" = "1,2,3" ] || { echo "FEHLER: Wiederherstellung ergab '$rows'"; fail=1; }
[ "$fail" -eq 0 ] && echo "Backup-Test ok" || { echo "Backup-Test FEHLGESCHLAGEN"; exit 1; }
