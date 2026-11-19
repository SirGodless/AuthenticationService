#!/usr/bin/env bash
# Ersteinrichtung: schreibt die .env mit frisch erzeugten Schlüsseln, wählt das Docker-Netzwerk von Caddy aus der Liste der
# vorhandenen aus und fragt die paar Angaben ab, die zu deinem Server gehören. Danach reicht: docker compose up -d --build
#
#   ./setup.sh                  fragt interaktiv (Enter nimmt den Vorschlag)
#   ./setup.sh --yes            fragt nichts, nimmt Vorschläge und Optionen (Netzwerk: caddy_net, wenn es existiert)
#   Optionen: --network NAME  --public-url URL  --mail-domain DOMAIN  --backup-dir PFAD  --env-file PFAD  --show-secrets
#
# Sicher wiederholbar: Vorhandene Werte in der .env bleiben unverändert. Nur fehlende Werte und Platzhalter
# ("bitte-aendern") werden gesetzt. Die Schlüssel werden nie neu erzeugt, wenn schon einer da ist: Sonst wären gespeicherte
# 2FA-Geheimnisse, Passwörter und Signierschlüssel unlesbar.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$HERE/.env.example"
ENV_FILE="$HERE/.env"
YES=0; SHOW=0
OPT_NETWORK=""; OPT_URL=""; OPT_MAIL_DOMAIN=""; OPT_BACKUP_DIR=""

die() { echo "Fehler: $*" >&2; exit 1; }
say() { echo "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) YES=1 ;;
    --show-secrets) SHOW=1 ;;
    --network) OPT_NETWORK="${2:?--network braucht einen Namen}"; shift ;;
    --public-url) OPT_URL="${2:?--public-url braucht eine Adresse}"; shift ;;
    --mail-domain) OPT_MAIL_DOMAIN="${2:?--mail-domain braucht eine Domain}"; shift ;;
    --backup-dir) OPT_BACKUP_DIR="${2:?--backup-dir braucht einen Pfad}"; shift ;;
    --env-file) ENV_FILE="${2:?--env-file braucht einen Pfad}"; shift ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unbekannte Option: $1 (siehe ./setup.sh --help)" ;;
  esac
  shift
done

command -v docker >/dev/null 2>&1 || die "Docker ist nicht installiert."
docker compose version >/dev/null 2>&1 || die "Das Compose-Plugin fehlt (\"docker compose\")."
docker info >/dev/null 2>&1 || die "Der Docker-Dienst antwortet nicht (läuft er, und darfst du ihn benutzen?)."
[ -f "$TEMPLATE" ] || die "Die Vorlage $TEMPLATE fehlt."

# ---- Hilfen ----

# Frage mit Vorschlag. Antwort leer oder kein Eingabekanal: Vorschlag. Mit --yes wird nicht gefragt.
ask() {   # ask "Text" "Vorschlag" -> Ergebnis in $REPLY
  local text="$1" default="$2" answer=""
  if [ "$YES" -eq 1 ]; then REPLY="$default"; return; fi
  read -r -p "$text [$default]: " answer || answer=""
  REPLY="${answer:-$default}"
}

random_b64() {   # 32 Zufallsbytes als Base64, so wie es die Schlüssel verlangen
  if command -v openssl >/dev/null 2>&1; then openssl rand -base64 "${1:-32}" | tr -d '\n'
  else head -c "${1:-32}" /dev/urandom | base64 | tr -d '\n'; fi
}

declare -A OLD=()       # Werte aus einer vorhandenen .env
declare -A NEW=()       # Werte, die in die neue Datei kommen
CHANGED=()              # was gesetzt oder geändert wurde (nur Namen, nie Werte)

if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    [[ "$line" == *=* ]] || continue
    OLD["${line%%=*}"]="${line#*=}"
  done < "$ENV_FILE"
fi

is_set() { local v="${OLD[$1]:-}"; [ -n "$v" ] && [ "$v" != "bitte-aendern" ]; }

# Wert festlegen: Vorhandenes bleibt, sonst der angegebene Wert
keep_or_set() {   # keep_or_set NAME WERT
  if is_set "$1"; then NEW["$1"]="${OLD[$1]}"; else NEW["$1"]="$2"; CHANGED+=("$1"); fi
}

# ---- Schlüssel ----

say "== Schlüssel =="
for key in TOTP_ENCRYPTION_KEY PASSWORD_PEPPER_KEY OIDC_KEY_ENCRYPTION_KEY; do
  if is_set "$key"; then say "  $key: schon gesetzt, bleibt unverändert"; NEW["$key"]="${OLD[$key]}"
  else
    # Die drei Schlüssel müssen verschieden sein (der Dienst startet sonst nicht)
    while :; do
      candidate="$(random_b64 32)"; clash=0
      for other in TOTP_ENCRYPTION_KEY PASSWORD_PEPPER_KEY OIDC_KEY_ENCRYPTION_KEY; do
        [ "${NEW[$other]:-${OLD[$other]:-}}" = "$candidate" ] && clash=1
      done
      [ "$clash" -eq 0 ] && break
    done
    NEW["$key"]="$candidate"; CHANGED+=("$key"); say "  $key: neu erzeugt"
  fi
done
if is_set DB_PASSWORD; then say "  DB_PASSWORD: schon gesetzt, bleibt unverändert"; NEW[DB_PASSWORD]="${OLD[DB_PASSWORD]}"
else
  # ohne + / = , damit nichts in Adressen oder Shell-Zeilen stört
  NEW[DB_PASSWORD]="$(random_b64 24 | tr '+/' '-_' | tr -d '=')"; CHANGED+=(DB_PASSWORD); say "  DB_PASSWORD: neu erzeugt"
fi

# ---- Docker-Netzwerk von Caddy ----

say; say "== Docker-Netzwerk von Caddy =="
mapfile -t NETS < <(docker network ls --filter driver=bridge --format '{{.Name}}' | grep -v -E '^(bridge|host|none)$' | grep -v -E '^authservice_' | sort)

describe_network() {
  local n="$1" subnets members
  subnets="$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' "$n" 2>/dev/null | xargs)"
  members="$(docker network inspect -f '{{range .Containers}}{{.Name}} {{end}}' "$n" 2>/dev/null | xargs | cut -c1-70)"
  printf '%s  [%s]  %s' "$n" "${subnets:-kein Adressbereich}" "${members:+Container: $members}"
}

NETWORK=""
has_net() { [ "${#NETS[@]}" -gt 0 ] && printf '%s\n' "${NETS[@]}" | grep -qx "$1"; }
if [ -n "$OPT_NETWORK" ]; then
  NETWORK="$OPT_NETWORK"
elif [ "$YES" -eq 1 ]; then
  if is_set CADDY_NETWORK; then NETWORK="${OLD[CADDY_NETWORK]}"
  elif has_net caddy_net; then NETWORK="caddy_net"
  else die "Kein Netzwerk gewählt und caddy_net existiert nicht. Mit --network NAME eines angeben. Vorhanden: ${NETS[*]:-(keine)}"; fi
elif [ "${#NETS[@]}" -eq 0 ]; then
  say "  Es gibt noch kein passendes Docker-Netzwerk."
  ask "  Name für ein neues Netzwerk, das du mit Caddy verbindest" "${OLD[CADDY_NETWORK]:-caddy_net}"; NETWORK="$REPLY"
else
  say "  Vorhandene Netzwerke (in diesem hängt dein Caddy):"
  default_index=1; i=1
  for n in "${NETS[@]}"; do
    say "   $i) $(describe_network "$n")"
    if [ "$n" = "${OLD[CADDY_NETWORK]:-caddy_net}" ]; then default_index=$i; fi
    i=$((i+1))
  done
  say "   n) ein neues Netzwerk anlegen"
  ask "  Nummer oder Name" "$default_index"
  choice="$REPLY"
  if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "${#NETS[@]}" ]; then NETWORK="${NETS[$((choice-1))]}"
  elif [ "$choice" = "n" ]; then ask "  Name des neuen Netzwerks" "caddy_net"; NETWORK="$REPLY"
  else NETWORK="$choice"; fi
fi
[[ "$NETWORK" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "Ungültiger Netzwerkname: $NETWORK"

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  if [ "$YES" -eq 1 ]; then die "Das Netzwerk $NETWORK gibt es nicht."; fi
  ask "  Das Netzwerk $NETWORK gibt es noch nicht. Jetzt anlegen? (j/n)" "j"
  case "$REPLY" in j|J|y|Y|ja|yes) docker network create "$NETWORK" >/dev/null; say "  Netzwerk $NETWORK angelegt. Verbinde deinen Caddy damit." ;; *) die "Ohne Netzwerk geht es nicht." ;; esac
fi
SUBNETS="$(docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}},{{end}}' "$NETWORK" | sed 's/,$//')"
[ -n "$SUBNETS" ] || die "Das Netzwerk $NETWORK hat keinen Adressbereich."
say "  Gewählt: $NETWORK ($SUBNETS)"
if ! docker network inspect -f '{{range .Containers}}{{.Name}} {{end}}' "$NETWORK" | grep -qi caddy; then
  say "  Hinweis: In diesem Netzwerk hängt (noch) kein Container mit \"caddy\" im Namen. Caddy muss darin sein, sonst erreicht er die Container nicht."
fi
# Auch bei einer vorhandenen .env gilt: Wer ein Netzwerk wählt, bekommt dessen Adressbereich
if [ "${OLD[CADDY_NETWORK]:-}" != "$NETWORK" ] || [ "${OLD[TRUSTED_PROXIES]:-}" != "$SUBNETS" ]; then CHANGED+=(CADDY_NETWORK TRUSTED_PROXIES); fi
NEW[CADDY_NETWORK]="$NETWORK"; NEW[TRUSTED_PROXIES]="$SUBNETS"

# ---- Angaben zu deinem Server ----

say; say "== Adressen und Mail =="
set_final() {   # set_final NAME WERT: Der gewählte Wert gilt (er war in der Frage schon mit dem bisherigen Wert vorbelegt)
  if [ "${OLD[$1]:-}" != "$2" ]; then CHANGED+=("$1"); fi
  NEW["$1"]="$2"
}
ask "  Adresse des Hubs (der Issuer, ändert sich nie wieder)" "${OPT_URL:-${OLD[PUBLIC_BASE_URL]:-https://auth.anfall.net}}"; URL="${REPLY%/}"
[[ "$URL" =~ ^https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || die "Die Adresse muss mit https:// beginnen, einen Host haben und darf keinen Pfad und keinen Slash am Ende haben: $URL"
host="${URL#https://}"
ask "  Domain für Mails (Absender, Mailserver)" "${OPT_MAIL_DOMAIN:-${OLD[MAIL_DOMAIN]:-${host#auth.}}}"; MAIL_DOMAIN="$REPLY"
[[ "$MAIL_DOMAIN" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}$ ]] || die "Keine gültige Domain: $MAIL_DOMAIN"
set_final PUBLIC_BASE_URL "$URL"; set_final MAIL_DOMAIN "$MAIL_DOMAIN"
# Abgeleitetes nur setzen, wenn noch nichts da ist (eigene Anpassungen bleiben)
keep_or_set MAIL_FROM "no-reply@$MAIL_DOMAIN"; keep_or_set MAIL_HOSTNAME "mail.$MAIL_DOMAIN"; keep_or_set TOTP_ISSUER "$MAIL_DOMAIN"

say; say "== Backup =="
ask "  Ordner für die Backups (bleibt auf diesem Server)" "${OPT_BACKUP_DIR:-${OLD[BACKUP_DIR]:-./backups}}"; BACKUP_DIR="$REPLY"
set_final BACKUP_DIR "$BACKUP_DIR"
keep_or_set BACKUP_TZ "Europe/Berlin"

# Standardwerte der Vorlage für alles andere (Argon2, Mail-Verbindung, ...) übernehmen, wenn nichts Eigenes da ist
while IFS= read -r line; do
  case "$line" in ''|'#'*) continue ;; esac
  [[ "$line" == *=* ]] || continue
  k="${line%%=*}"; v="${line#*=}"
  [ -n "${NEW[$k]+x}" ] && continue
  if [ -n "${OLD[$k]:-}" ]; then NEW["$k"]="${OLD[$k]}"; else NEW["$k"]="$v"; fi
done < "$TEMPLATE"

# ---- .env schreiben ----

build_env() {
  while IFS= read -r line; do
    if [[ "$line" == *=* && "$line" != '#'* ]]; then
      k="${line%%=*}"; printf '%s=%s\n' "$k" "${NEW[$k]}"
    else printf '%s\n' "$line"; fi
  done < "$TEMPLATE"
  # Eigene Einträge aus einer vorhandenen .env, die die Vorlage nicht kennt, bleiben erhalten
  local extra=0
  for k in "${!OLD[@]}"; do
    if ! grep -q "^$k=" "$TEMPLATE"; then
      [ "$extra" -eq 0 ] && { printf '\n# --- Weitere Einträge aus der bisherigen .env ---\n'; extra=1; }
      printf '%s=%s\n' "$k" "${OLD[$k]}"
    fi
  done
}

say; say "== .env schreiben =="
TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
chmod 600 "$TMP"
build_env > "$TMP"
if [ -f "$ENV_FILE" ] && cmp -s "$TMP" "$ENV_FILE"; then
  say "  Nichts zu ändern: Die .env ist schon vollständig."
else
  if [ -f "$ENV_FILE" ]; then cp -p "$ENV_FILE" "$ENV_FILE.bak-$(date +%Y%m%d-%H%M%S)"; say "  Sicherungskopie der bisherigen Datei angelegt (.bak-...)."; fi
  cat "$TMP" > "$ENV_FILE"; chmod 600 "$ENV_FILE"
  say "  $ENV_FILE geschrieben (nur für dich lesbar)."
  [ "${#CHANGED[@]}" -gt 0 ] && say "  Gesetzt oder geändert: $(printf '%s\n' "${CHANGED[@]}" | sort -u | xargs)"
fi

# ---- Backup-Ordner ----

case "$BACKUP_DIR" in /*) ABS_BACKUP="$BACKUP_DIR" ;; *) ABS_BACKUP="$HERE/${BACKUP_DIR#./}" ;; esac
mkdir -p "$ABS_BACKUP" 2>/dev/null || true
if [ -d "$ABS_BACKUP" ]; then
  if [ "$(stat -c %u "$ABS_BACKUP")" != "70" ]; then
    # Der Backup-Container läuft ohne Root-Rechte als Benutzer 70 und muss dort schreiben dürfen
    if chown 70:70 "$ABS_BACKUP" 2>/dev/null || sudo -n chown 70:70 "$ABS_BACKUP" 2>/dev/null; then say "  Backup-Ordner $ABS_BACKUP gehört jetzt dem Benutzer 70."
    else say "  Noch zu tun (braucht Root-Rechte):  sudo chown 70:70 $ABS_BACKUP"; fi
  fi
else
  say "  Noch zu tun:  sudo mkdir -p $ABS_BACKUP && sudo chown 70:70 $ABS_BACKUP"
fi

# ---- Prüfen ----

CHECK_ERR="$(mktemp)"
if docker compose --project-directory "$HERE" --env-file "$ENV_FILE" config -q 2>"$CHECK_ERR"; then
  say "  Compose liest die .env ohne Fehler."
else
  cat "$CHECK_ERR" >&2; rm -f "$CHECK_ERR"; die "docker compose meldet einen Fehler mit dieser .env (liegt UserHub neben diesem Ordner?)."
fi
rm -f "$CHECK_ERR"

say
say "Fertig. Nächste Schritte:"
say "  1. Den Pepper getrennt vom Backup aufbewahren (z. B. im Passwortmanager):  grep PASSWORD_PEPPER_KEY $ENV_FILE"
say "  2. Starten:  docker compose up -d --build"
say "  3. Den Block aus deploy/Caddyfile.snippet in deinen Caddy eintragen (siehe docs/DEPLOY.md, Schritt 5)."
if [ "$SHOW" -eq 1 ]; then say; say "Schlüssel (nur auf Wunsch angezeigt):"; grep -E '^(DB_PASSWORD|TOTP_ENCRYPTION_KEY|PASSWORD_PEPPER_KEY|OIDC_KEY_ENCRYPTION_KEY)=' "$ENV_FILE" | sed 's/^/  /'; fi
