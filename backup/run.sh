#!/bin/sh
# Zeitplan: Jeden Tag kurz nach 0 Uhr (Zeitzone TZ, Standard Europe/Berlin) ein Backup. Läuft als Schleife, die alle 30
# Sekunden nachsieht, ob für den heutigen Tag schon eines existiert. Das übersteht Neustarts, Zeitumstellungen und eine
# kurz nicht erreichbare Datenbank (neuer Versuch nach 5 Minuten). Beim Start entsteht sofort ein Backup, falls für heute noch keines da ist.
export TZ="${TZ:-Europe/Berlin}"
DIR="${BACKUP_DIR:-/backups}"
echo "Backup-Zeitplan gestartet (Zeitzone $TZ)"
while true; do
  today=$(date +%F)
  if [ ! -f "$DIR/daily/auth-$today.dump" ]; then
    if ! backup.sh; then
      echo "Backup fehlgeschlagen, neuer Versuch in 5 Minuten" >&2
      sleep 300
      continue
    fi
  fi
  sleep 30
done
