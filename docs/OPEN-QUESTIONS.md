# Offene Fragen an Joshua

Stand: nach Pepper und Umschnitt der Migrationen (13.11.2026).

## Erledigt (Antworten vom 13.11.2026)

- **Bounces:** Es gibt keinen Posteingang. Postfix verwirft Rückläufer (`decisions/mail.md`).
- **2FA einrichten für normale User:** Mit Etappe 4 (API, Seite, Pflicht für Admins).
- **NIST ordentlich umsetzen:** Erledigt, Abgleich in `docs/NIST-800-63B-4.md`.
- **Subnetz von `caddy_net`:** `172.22.0.0/16` (`TRUSTED_PROXIES`).
- **Backup-Konzept:** monatlich (1 Jahr), täglich um 0 Uhr (7 Tage), Ziel bleibt auf dem Server. Umsetzung in Etappe 7.
- **Pepper:** Eingebaut (`PASSWORD_PEPPER_KEY`).
- **Passwort-Reset durch Admins erzwingen:** Kommt mit Etappe 4.
- **Migrationen:** Noch nichts deployt, deshalb nach Themen umgeschnitten (Schema vorher und nachher identisch geprüft).

## Zu entscheiden

1. **Hub-UI an der API:** Ich gehe von Variante 1 aus (Sitzungs-Cookie, CORS für `anfall.net`), siehe `docs/decisions/hub-ui-authentication.md`. Gilt als Annahme, bis du sie bestätigst. Gebaut wird sie erst mit der Hub-Schnittstelle (Etappe 4 bis 6).
2. **Zeitzone für "0 Uhr" beim Backup:** Ich nehme Europe/Berlin an, sag Bescheid, wenn es UTC sein soll (reicht bis Etappe 7).

## Nicht verifiziert

- **[U]** Auslieferung der Mails an Gmail, Outlook und andere sowie die Spam-Bewertung (neue IP ohne Ruf). Nicht prüfbar in der Cloud-Sitzung.
- **[U]** Dass Port 25 auf deinem Server ausgehend wirklich offen ist (Prüfbefehl in der README).
- **[U]** Name des Menüpunkts für Reverse DNS in der Hetzner-Oberfläche.
- **[U]** Quelle und Nutzungsbedingungen der NCSC-Liste selbst (SecLists steht unter MIT, die Liste stammt vom UK NCSC).
- **[U]** `pages.nist.gov` war nicht erreichbar, der NIST-Text stammt aus dem offiziellen GitHub-Repo von NIST.
- **[U]** `caddy_net` und `/opt/containers/` aus deinen Infrastruktur-Notizen (das Subnetz ist bestätigt).
- **[U]** Docker-Build auf deinem Server (ich konnte ihn nur mit einem Umweg für das Proxy-Zertifikat der Sitzung testen).
- **[U]** Dauer eines Argon2-Hashs auf deinem Zielsystem (Log beim Start prüfen).
