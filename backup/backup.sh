#!/bin/sh
# Ein Backup jetzt: Datenbank-Dump, Schlüssel und DKIM-Schlüssel in /backups/daily, am Monatsersten zusätzlich eine
# Kopie in /backups/monthly. Danach alte Dateien löschen: täglich nach 7 Tagen, monatlich nach einem Jahr.
# Das Datum steckt im Dateinamen, die Aufbewahrung rechnet damit (nicht mit Dateizeiten).
# BACKUP_TODAY (JJJJ-MM-TT) ist nur für Tests: So lässt sich ein Ablauf über viele Tage nachstellen.
set -eu
DIR="${BACKUP_DIR:-/backups}"
TODAY="${BACKUP_TODAY:-$(date +%F)}"
umask 077
mkdir -p "$DIR/daily" "$DIR/monthly"

echo "Backup für $TODAY"
# erst in eine Zwischendatei, damit nie ein halber Dump unter dem richtigen Namen liegt
pg_dump -Fc -f "$DIR/daily/.auth-$TODAY.tmp"
mv "$DIR/daily/.auth-$TODAY.tmp" "$DIR/daily/auth-$TODAY.dump"

# Die beiden Schlüssel, ohne die TOTP-Geheimnisse und OIDC-Signierschlüssel nach einer Wiederherstellung unlesbar sind.
# Der Pepper steht bewusst NICHT hier (er soll nicht neben dem Dump liegen).
printf 'TOTP_ENCRYPTION_KEY=%s\nOIDC_KEY_ENCRYPTION_KEY=%s\n' "${TOTP_ENCRYPTION_KEY:-}" "${OIDC_KEY_ENCRYPTION_KEY:-}" > "$DIR/daily/keys-$TODAY.env"

# DKIM-Schlüssel des Mailservers (sonst muss der DNS-Eintrag neu gesetzt werden)
if [ -d /mail-dkim ]; then
  tar -czf "$DIR/daily/dkim-$TODAY.tgz" -C /mail-dkim . 2>/dev/null || echo "Warnung: DKIM-Schlüssel nicht lesbar, nicht gesichert"
fi

# Monatsanfang: Kopie für ein Jahr
case "$TODAY" in
  *-01) cp -p "$DIR"/daily/*"-$TODAY".* "$DIR/monthly/" ;;
esac

# Aufbewahrung: Grenze als Datum rechnen, mittags, damit Zeitumstellungen nichts verschieben
noon=$(date -d "$TODAY 12:00:00" +%s)
cutoff() { date -d "@$((noon - $1 * 86400))" +%F | tr -d -; }
prune() {   # prune <Ordner> <Tage>: löscht Dateien, deren Datum im Namen auf oder vor der Grenze liegt
  limit=$(cutoff "$2")
  for f in "$1"/*; do
    [ -f "$f" ] || continue
    d=$(basename "$f" | sed -n 's/.*\([0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}\).*/\1/p' | tr -d -)
    [ -n "$d" ] && [ "$d" -le "$limit" ] && rm -f "$f" && echo "gelöscht: $f"
  done
  return 0
}
prune "$DIR/daily" 7
prune "$DIR/monthly" 365

echo "$TODAY" > "$DIR/.last_ok"
echo "Backup fertig"
