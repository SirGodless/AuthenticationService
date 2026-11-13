# Offene Fragen an Joshua

Stand: Etappe 2 (12.11.2026).

## Erledigt (Antworten vom 12.11.2026)

- Branches wurden gemerged. Weiterarbeit auf demselben Sitzungs-Branch, ein PR pro Etappe.
- E-Mail des ersten Admins gilt als bestätigt, **nur** beim Setup-Link.
- Eigener Mailserver im Stack, ausgehend Port 25 wird auf dem Server freigegeben, Beschreibung in `README.md`.
- Backup inklusive `TOTP_ENCRYPTION_KEY`. Das Backup-Konzept selbst (Häufigkeit, Ziel) bleibt offen, siehe unten.

## Zu entscheiden

1. **Bounces:** Rückläufer gehen an die Absenderadresse (`MAIL_FROM`, Standard `no-reply@anfall.net`). Gibt es dafür ein Postfach oder eine Weiterleitung auf anfall.net? Sonst gehen Fehlermeldungen von Empfängern verloren. Alternative: eine Adresse, die du liest.
2. **2FA einrichten und abschalten für normale User:** Der Plan sieht es auf der Profilseite vor (Etappe 6). Bis dahin gibt es dafür keine API. Heißt: Nach `auth-admin reset-2fa` ist ein Admin ohne 2FA, bis Etappe 6 steht. Ist das in Ordnung, oder soll die kleine API (einrichten, Wiederherstellungscodes neu erzeugen) schon in Etappe 3 oder 4 kommen, weil ab dort Admin-Funktionen die 2FA verlangen?
3. **Passwort enthält Username oder E-Mail:** NIST SP 800-63B-4 empfiehlt, auch kontextbezogene Wörter (Dienstname, Username) zu sperren. Steht nicht in deiner Spezifikation, ich habe es nicht gebaut. Soll ich?
4. **Sperrliste:** Bei 15 Zeichen Mindestlänge trifft die NCSC-Liste fast nie (359 von 99.840 Einträgen sind lang genug). Soll später der Pwned-Passwords-Index (offline) dazukommen? Das Interface dafür ist vorbereitet.
5. **Hub-UI an der API (vor Etappe 3):** Der Hub liegt laut Spezifikation auf `anfall.net`, die API und das Login auf `auth.anfall.net`. Das Sitzungs-Cookie gilt nur für `auth.anfall.net`. Mein Vorschlag mit Begründung folgt zu Beginn von Etappe 3.
6. **Backup-Konzept der Datenbank:** Häufigkeit, Ziel, Aufbewahrung. In `DEPLOY.md` steht ein Dump-Befehl als Platzhalter.
7. **Caddy-Adresse für die Echt-IP:** Aktuell wird `X-Forwarded-For` aus dem ganzen Docker-Netz geglaubt. Wenn du mir die feste Adresse oder das Subnetz von `caddy_net` nennst, schränke ich es darauf ein (`app.trusted-proxies`).

## Nicht verifiziert

- **[U]** Auslieferung der Mails an Gmail, Outlook und andere sowie die Spam-Bewertung (neue IP ohne Ruf). Nicht prüfbar in der Cloud-Sitzung.
- **[U]** Dass Port 25 auf deinem Server ausgehend wirklich offen ist (Prüfbefehl in der README).
- **[U]** Name des Menüpunkts für Reverse DNS in der Hetzner-Oberfläche.
- **[U]** Quelle und Nutzungsbedingungen der NCSC-Liste selbst (SecLists steht unter MIT, die Liste stammt vom UK NCSC).
- **[U]** Beide Starter-Namen für den Authorization Server (Klärung zu Beginn von Etappe 3).
- **[U]** `caddy_net` und `/opt/containers/` aus deinen Infrastruktur-Notizen.
- **[U]** Docker-Build auf deinem Server (ich konnte ihn nur mit einem Umweg für das Proxy-Zertifikat der Sitzung testen).
- **[U]** Dauer eines Argon2-Hashs auf deinem Zielsystem (Log beim Start prüfen).
