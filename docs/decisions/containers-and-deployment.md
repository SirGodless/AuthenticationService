# Container und Deployment

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Feste Images:** `postgres:17.11-alpine`, `axllent/mailpit:v1.29.7`, nginx 1.30-alpine (Minor-Version fest). Die Java-Images (`maven:3.9-eclipse-temurin-21-alpine`, `eclipse-temurin:21-jre-alpine`) sind auf Hauptversion festgelegt, damit Sicherheitsupdates beim Neubauen ankommen.
- **[A] Nicht-Root:** Backend (UID 10001) und UI (nginx, UID 101) laufen ohne Root. Beim PostgreSQL-Image startet der Einstiegspunkt kurz als Root und gibt dann Rechte ab, der Datenbankprozess läuft als `postgres`.
- **[A] Netzwerke:** `caddy_net` (extern, gehört Caddy) für Backend und UI, `internal` (nur intern, ohne Zugang von außen) für die Datenbank und Mailpit. Der Name `caddy_net` und der Ablageort `/opt/containers/` stammen aus Joshuas Infrastruktur-Notizen und sind **[U]** nicht von mir geprüft.
- **Ablage der Repos in der Cloud-Sitzung (geprüft):** `AuthenticationService/` und `UserHub/` liegen nebeneinander in `/home/user`. `docker-compose.yml` baut die UI aus `../UserHub`, das funktioniert.
- **Nur in der Cloud-Sitzung nötig, nicht im Repo:** Der Docker-Build im Container scheiterte hier an einem unbekannten Zertifikat des Sandbox-Proxys (PKIX), nicht am Dockerfile. Ich habe mit einer Kopie des Dockerfiles getestet, die das Proxy-Zertifikat importiert. Auf Joshuas Server tritt das nicht auf. Er sollte beim ersten `docker compose up -d --build` dennoch prüfen, dass der Build durchläuft.
- **[A] Branches:** Beide Repos arbeiten auf dem von der Sitzung vorgegebenen Branch `claude/relaxed-feynman-ng4azc`. Abschnitt 0 verlangt einen Branch pro Etappe. Siehe offene Frage in `OPEN-QUESTIONS.md`.

## Etappe 2

Von Joshua bestätigt (12.11.2026): Branches sind gemerged, die E-Mail gilt **nur beim Setup-Link des ersten Admins** als bestätigt (so umgesetzt, alle anderen Konten müssen per Mail bestätigen), ein **eigener Mailserver** gehört in den Stack, Backup inklusive `TOTP_ENCRYPTION_KEY`.

- **[E] Backup-Konzept (Joshua, 13.11.2026):** Am Anfang jedes Monats ein Backup, das 1 Jahr aufbewahrt wird. Zusätzlich jeden Tag um 0 Uhr eines, das nach 7 Tagen gelöscht wird. Umsetzung (Container mit Zeitplan, Aufbewahrung, Wiederherstellung) kommt in Etappe 7 zusammen mit `docs/DEPLOY.md`. Zu klären dort: Ziel des Backups (ein Backup auf demselben Server schützt nicht vor Serverausfall), Zeitzone für "0 Uhr", und dass `TOTP_ENCRYPTION_KEY` und das Volume `mail-dkim` dazugehören.
