# Offene Fragen an Joshua

Stand: Etappe 1 (12.11.2026).

## Zu entscheiden

1. **Branch pro Etappe.** Die Sitzung gibt für beide Repos den Branch `claude/relaxed-feynman-ng4azc` vor, auf dem ich pushen soll. Abschnitt 0 sagt "ein Branch pro Etappe und ein Pull Request". Möchtest du für jede Etappe einen eigenen Branch (z. B. `etappe-2-konten-login`) oder bleibt es bei einem Sitzungs-Branch mit einem PR pro Etappe? Gepusht ist auf diesen Sitzungs-Branch, einen PR habe ich nicht erstellt, bis du es sagst.
2. **E-Mail des ersten Admins ist bestätigt** (siehe DECISIONS.md). Einverstanden?
3. **Mailversand (für Etappe 2).** Welcher SMTP-Server soll genutzt werden (eigener Mailserver, Host, Port, Verschlüsselung)? Abschnitt 3 verbietet externe Mail-Dienste. **[U]** Ob die Hetzner-Portsperre für deinen Server gilt (Port 25 ausgehend), ist offen und betrifft den Versand. Ich brauche die Angaben vor dem Deployment, nicht vor dem Bau.
4. **Wie meldet sich die Hub-UI an der API an?** (Abschnitt 8, offen, vor Etappe 3 nötig.) Mein Vorschlag mit Begründung folgt in `DECISIONS.md` zu Beginn von Etappe 3, ich frage dann.
5. **Backup der Datenbank** (Abschnitt 2). In `DEPLOY.md` steht ein Dump-Befehl als Platzhalter. Wie willst du Backups haben (Häufigkeit, Ziel, Aufbewahrung)? Wichtig: Der `TOTP_ENCRYPTION_KEY` gehört mit ins Backup, sonst sind die 2FA-Geheimnisse nach einer Wiederherstellung unlesbar.

## Nicht verifiziert

- **[U]** Beide Starter-Namen für den Authorization Server (siehe DECISIONS.md), Klärung zu Beginn von Etappe 3.
- **[U]** `caddy_net` und `/opt/containers/` aus deinen Infrastruktur-Notizen.
- **[U]** Docker-Build auf deinem Server (Ich konnte ihn nur mit Proxy-Zertifikat-Umweg testen, siehe DECISIONS.md).
- **[U]** Dauer eines Argon2-Hashs auf deinem Zielsystem (Log beim Start prüfen).
