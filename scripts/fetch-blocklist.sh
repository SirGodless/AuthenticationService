#!/bin/sh
# Lädt die Sperrliste bekannter Passwörter (NCSC, 100.000 häufigste geleakte Passwörter, aus SecLists)
# und prüft die Prüfsumme. Aufruf: scripts/fetch-blocklist.sh [Zielordner]  (Standard: ./data)
# Der Docker-Build ruft dasselbe Skript auf, die Liste liegt nicht im Git.
#
# Version und Prüfsumme sind festgenagelt: Es kommt immer genau diese Datei, nie eine veränderte.
# SecLists steht unter MIT-Lizenz (LICENSE im Repo geprüft am 12.11.2026). Mehr in docs/THIRD-PARTY.md.
set -eu

COMMIT=dbfb830d95e4f3da4fb7d7d277ed597de0ed7a18
SHA256=c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0
FILE=100k-most-used-passwords-NCSC.txt
URL="https://raw.githubusercontent.com/danielmiessler/SecLists/${COMMIT}/Passwords/Common-Credentials/${FILE}"

TARGET="${1:-data}"
mkdir -p "$TARGET"

if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$URL" -o "$TARGET/$FILE"
else
  wget -q -O "$TARGET/$FILE" "$URL"
fi

echo "${SHA256}  ${TARGET}/${FILE}" | sha256sum -c -
echo "Sperrliste gespeichert: ${TARGET}/${FILE}"
