# Offene Fragen an Joshua

Stand: Zwischenetappe nach Etappe 2 (13.11.2026).

## Erledigt (Antworten vom 13.11.2026)

- **Bounces:** Es gibt keinen Posteingang. Postfix verwirft Rückläufer (siehe `decisions/mail.md`).
- **2FA einrichten für normale User:** Ich habe entschieden, es mit Etappe 4 zu bauen (API, Seite, Pflicht für Admins), nicht früher. Grund: Etappe 3 (OIDC) braucht es nicht, ab Etappe 4 gibt es Admin-Funktionen, vor denen die 2FA Pflicht ist. Bis dahin hilft `auth-admin reset-2fa` nur beim Entfernen, nicht beim Wiederherstellen. Es ist noch nichts deployt, deshalb ist die Lücke ohne Folgen.
- **NIST ordentlich umsetzen:** Erledigt, mit Korrekturen. Details in `docs/NIST-800-63B-4.md`.
- **Subnetz von `caddy_net`:** `172.22.0.0/16` ist Standard in `docker-compose.yml` (`TRUSTED_PROXIES`).
- **Backup-Konzept:** Monatlich zum Monatsanfang (1 Jahr aufbewahrt), täglich um 0 Uhr (7 Tage aufbewahrt). Umsetzung in Etappe 7.
- **Hub-UI an der API:** Vorschlag kommt zu Beginn von Etappe 3.

## Zu entscheiden

1. **Pepper für Passwort-Hashes (NIST SHOULD).** NIST empfiehlt, den Hash zusätzlich mit einem geheimen Schlüssel zu verrechnen, der getrennt von der Datenbank liegt. Nutzen: Ein gestohlener Datenbank-Dump (etwa aus einem Backup) lässt sich ohne den Schlüssel nicht offline knacken. Kosten: Ein weiterer Schlüssel in `.env`, der mit ins Backup muss (aber getrennt vom Datenbank-Backup liegen sollte, sonst bringt es nichts). Geht der Schlüssel verloren, gelten alle Passwörter nicht mehr, es bleibt der Reset per Mail. Meine Empfehlung: ja, einbauen, wenn das Backup-Ziel (Etappe 7) nicht dieselbe Stelle wie die `.env` ist. Soll ich?
2. **Passwort-Reset durch Admins erzwingen.** NIST verlangt, bei Hinweis auf Kompromittierung einen Wechsel zu erzwingen. Admin-Funktionen sind in der Spezifikation: User sehen, bannen, 2FA zurücksetzen. Ein "Passwort-Reset erzwingen" (beendet Sitzungen, schickt Reset-Mail) steht nicht drin. Soll es in Etappe 4 dazu?
3. **Migrationen V1 und V2.** Ich habe sie nicht nach Themen umgeschnitten, weil eine geänderte Migration auf einer schon gestarteten Datenbank nicht mehr startet. Hast du den Stack schon irgendwo mit Datenbank gestartet? Wenn nein, schneide ich sie vor dem ersten Deployment sauber nach Themen um.
4. **Zeitzone für "0 Uhr" beim Backup** (Europe/Berlin oder UTC) und **Ziel des Backups.** Ein Backup auf demselben Server schützt nicht vor Serverausfall. Wohin sollen sie zusätzlich? (Antwort reicht bis Etappe 7.)

## Nicht verifiziert

- **[U]** Auslieferung der Mails an Gmail, Outlook und andere sowie die Spam-Bewertung (neue IP ohne Ruf). Nicht prüfbar in der Cloud-Sitzung.
- **[U]** Dass Port 25 auf deinem Server ausgehend wirklich offen ist (Prüfbefehl in der README).
- **[U]** Name des Menüpunkts für Reverse DNS in der Hetzner-Oberfläche.
- **[U]** Quelle und Nutzungsbedingungen der NCSC-Liste selbst (SecLists steht unter MIT, die Liste stammt vom UK NCSC).
- **[U]** `pages.nist.gov` war nicht erreichbar, der NIST-Text stammt aus dem offiziellen GitHub-Repo von NIST.
- **[U]** Beide Starter-Namen für den Authorization Server (Klärung zu Beginn von Etappe 3).
- **[U]** `caddy_net` und `/opt/containers/` aus deinen Infrastruktur-Notizen (das Subnetz ist jetzt bestätigt).
- **[U]** Docker-Build auf deinem Server (ich konnte ihn nur mit einem Umweg für das Proxy-Zertifikat der Sitzung testen).
- **[U]** Dauer eines Argon2-Hashs auf deinem Zielsystem (Log beim Start prüfen).
