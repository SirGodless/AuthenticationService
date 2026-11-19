#!/usr/bin/env bash
# Prüft setup.sh mit einem Wegwerf-Docker-Netzwerk und einer Wegwerf-.env (braucht Docker, rührt deine echte .env nicht an).
# Fälle: frische Einrichtung, Wiederholung ändert nichts, vorhandene Werte bleiben, Platzhalter werden ersetzt,
# Auswahl aus der Liste, fehlendes Netzwerk, ungültige Eingaben.
set -uo pipefail
cd "$(dirname "$0")/../.."
NET1=setup-test-net-a; NET2=setup-test-net-b
WORK="$(mktemp -d)"; FAIL=0
cleanup() { docker network rm "$NET1" "$NET2" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT
docker network rm "$NET1" "$NET2" >/dev/null 2>&1 || true
docker network create --subnet 10.231.0.0/24 "$NET1" >/dev/null
docker network create --subnet 10.231.1.0/24 "$NET2" >/dev/null

ok()   { echo "ok: $1"; }
fail() { echo "FAIL: $1"; FAIL=1; }
check() { if eval "$2"; then ok "$1"; else fail "$1"; fi; }
val() { grep -E "^$2=" "$1" | head -1 | cut -d= -f2-; }
run() { ./setup.sh "$@" >"$WORK/out.txt" 2>&1; }
b64len() { printf '%s' "$1" | base64 -d 2>/dev/null | wc -c; }

# 1. Frische Einrichtung ohne Fragen
ENV="$WORK/env1"
run --yes --network "$NET1" --env-file "$ENV" --backup-dir "$WORK/backups"
check "frische .env wird angelegt" '[ -f "$ENV" ]'
check ".env ist nur für den Besitzer lesbar" '[ "$(stat -c %a "$ENV")" = 600 ]'
for k in TOTP_ENCRYPTION_KEY PASSWORD_PEPPER_KEY OIDC_KEY_ENCRYPTION_KEY; do
  check "$k hat genau 32 Byte" '[ "$(b64len "$(val "$ENV" $k)")" = 32 ]'
done
check "die drei Schlüssel sind verschieden" '[ "$(for k in TOTP_ENCRYPTION_KEY PASSWORD_PEPPER_KEY OIDC_KEY_ENCRYPTION_KEY; do val "$ENV" $k; done | sort -u | wc -l)" = 3 ]'
check "DB_PASSWORD ist gesetzt und enthält keine Sonderzeichen" '[[ "$(val "$ENV" DB_PASSWORD)" =~ ^[A-Za-z0-9_-]{20,}$ ]]'
check "kein Platzhalter bleibt übrig" '! grep -q "bitte-aendern" "$ENV"'
check "Netzwerk und Adressbereich stammen aus dem gewählten Netzwerk" '[ "$(val "$ENV" CADDY_NETWORK)" = "$NET1" ] && [ "$(val "$ENV" TRUSTED_PROXIES)" = "10.231.0.0/24" ]'
check "Adresse, Mail und Backup kommen aus den Vorschlägen" '[ "$(val "$ENV" PUBLIC_BASE_URL)" = "https://auth.anfall.net" ] && [ "$(val "$ENV" MAIL_FROM)" = "no-reply@anfall.net" ] && [ "$(val "$ENV" MAIL_HOSTNAME)" = "mail.anfall.net" ] && [ "$(val "$ENV" BACKUP_TZ)" = "Europe/Berlin" ]'
check "Backup-Ordner wurde angelegt" '[ -d "$WORK/backups" ]'
check "Die Schlüssel stehen nicht in der Ausgabe" '! grep -q "$(val "$ENV" TOTP_ENCRYPTION_KEY)" "$WORK/out.txt"'
check "docker compose liest die Datei" 'docker compose --env-file "$ENV" config -q'
check "das Compose-Netzwerk bekommt den gewählten Namen" 'docker compose --env-file "$ENV" config | grep -q "name: $NET1"'

# 2. Wiederholung ändert nichts
cp "$ENV" "$WORK/env1.before"
run --yes --network "$NET1" --env-file "$ENV" --backup-dir "$WORK/backups"
check "zweiter Lauf lässt die Datei unverändert" 'cmp -s "$ENV" "$WORK/env1.before"'
check "zweiter Lauf meldet, dass nichts zu tun ist" 'grep -q "Nichts zu ändern" "$WORK/out.txt"'
check "ohne Änderung keine Sicherungskopie" '[ -z "$(ls "$WORK" | grep "env1.bak")" ]'

# 3. Eigene Werte bleiben, Platzhalter werden ersetzt, fremde Einträge bleiben
ENV="$WORK/env3"
{ echo "MAIL_FROM=hallo@meine-domain.de"; echo "DB_PASSWORD=bitte-aendern"; echo "TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)"; echo "MEIN_EIGENER_WERT=42"; } > "$ENV"
TOTP_BEFORE="$(val "$ENV" TOTP_ENCRYPTION_KEY)"
run --yes --network "$NET1" --env-file "$ENV" --backup-dir "$WORK/backups"
check "vorhandener TOTP-Schlüssel bleibt unverändert" '[ "$(val "$ENV" TOTP_ENCRYPTION_KEY)" = "$TOTP_BEFORE" ]'
check "eigene Absenderadresse bleibt" '[ "$(val "$ENV" MAIL_FROM)" = "hallo@meine-domain.de" ]'
check "Platzhalter beim DB-Passwort wird ersetzt" '[ "$(val "$ENV" DB_PASSWORD)" != "bitte-aendern" ] && [ -n "$(val "$ENV" DB_PASSWORD)" ]'
check "fremde Einträge bleiben erhalten" '[ "$(val "$ENV" MEIN_EIGENER_WERT)" = "42" ]'
check "von der alten Datei gibt es eine Sicherungskopie" '[ -n "$(ls "$WORK" | grep "env3.bak")" ]'

# 4. Auswahl aus der Liste (interaktiv, Antworten per Eingabe)
ENV="$WORK/env4"
IDX=$(docker network ls --filter driver=bridge --format '{{.Name}}' | grep -v -E '^(bridge|host|none)$' | grep -v -E '^authservice_' | sort | grep -nx "$NET2" | cut -d: -f1)
printf '%s\nhttps://auth.beispiel.de\nbeispiel.de\n%s\n' "$IDX" "$WORK/b4" | ./setup.sh --env-file "$ENV" >"$WORK/out4.txt" 2>&1
check "interaktiv: Netzwerk per Nummer gewählt" '[ "$(val "$ENV" CADDY_NETWORK)" = "$NET2" ] && [ "$(val "$ENV" TRUSTED_PROXIES)" = "10.231.1.0/24" ]'
check "interaktiv: eigene Adresse und Domain übernommen" '[ "$(val "$ENV" PUBLIC_BASE_URL)" = "https://auth.beispiel.de" ] && [ "$(val "$ENV" MAIL_DOMAIN)" = "beispiel.de" ] && [ "$(val "$ENV" MAIL_FROM)" = "no-reply@beispiel.de" ]'
check "interaktiv: die Liste zeigt Adressbereiche" 'grep -q "10.231.1.0/24" "$WORK/out4.txt"'

# 5. Netzwerk per Name statt Nummer, und Enter für alles (Vorschläge)
ENV="$WORK/env5"
printf '%s\n\n\n%s\n' "$NET1" "$WORK/b5" | ./setup.sh --env-file "$ENV" >/dev/null 2>&1
check "interaktiv: Netzwerk per Name" '[ "$(val "$ENV" CADDY_NETWORK)" = "$NET1" ]'

# 6. Fehlerfälle: nichts wird geschrieben
ENV="$WORK/env6"
run --yes --network gibt-es-nicht-xyz --env-file "$ENV"
check "unbekanntes Netzwerk bei --yes: Fehler, keine Datei" '[ ! -f "$ENV" ] && grep -q "gibt es nicht" "$WORK/out.txt"'
run --yes --network "$NET1" --public-url "http://unsicher.example" --env-file "$ENV"
check "Adresse ohne https wird abgelehnt" '[ ! -f "$ENV" ] && grep -q "https://" "$WORK/out.txt"'
run --yes --network "$NET1" --public-url "https://auth.beispiel.de/pfad" --env-file "$ENV"
check "Adresse mit Pfad wird abgelehnt" '[ ! -f "$ENV" ]'
run --yes --network "$NET1" --mail-domain "keine domain" --env-file "$ENV"
check "ungültige Mail-Domain wird abgelehnt" '[ ! -f "$ENV" ]'
run --yes --network "ungültig name!" --env-file "$ENV"
check "ungültiger Netzwerkname wird abgelehnt" '[ ! -f "$ENV" ]'
run --kaputt
check "unbekannte Option gibt eine Meldung" 'grep -q "Unbekannte Option" "$WORK/out.txt"'

[ "$FAIL" -eq 0 ] && echo "Setup-Test ok" || { echo "Setup-Test FEHLGESCHLAGEN"; exit 1; }
