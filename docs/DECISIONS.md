# Entscheidungen und Annahmen (Inhaltsverzeichnis)

Stand: Zwischenetappe nach Etappe 2 (13.11.2026). Die Einzelheiten stehen nach Fachthema in `docs/decisions/`.
Kennzeichnung wie in `CLAUDE.md`: **[E]** von Joshua entschieden, **[A]** Annahme (wartet auf Bestätigung), **[U]** nicht oder nicht ganz verifiziert.

## Themen

| Datei | Inhalt |
| :- | :- |
| [code-structure.md](decisions/code-structure.md) | Struktur beider Repos und warum, Regeln für Lesbarkeit |
| [backend-technology.md](decisions/backend-technology.md) | Versionen, Spring, Datenbankzugriff, Fehlercodes |
| [data-model.md](decisions/data-model.md) | Tabellen, Trennung personenbezogener Daten |
| [first-admin-setup.md](decisions/first-admin-setup.md) | Setup-Link, erster Admin, 2FA, Wiederherstellungscodes |
| [hub-ui-authentication.md](decisions/hub-ui-authentication.md) | Vorschlag: Wie sich die Hub-UI an der API anmeldet (Frage vor Etappe 3) |
| [login-and-sessions.md](decisions/login-and-sessions.md) | Anmeldung, Sitzungen, Cookies, CSRF |
| [rate-limits.md](decisions/rate-limits.md) | Limits, echte Besucher-IP |
| [passwords.md](decisions/passwords.md) | Passwortregeln nach NIST, Sperrliste, Kontextwörter |
| [mail.md](decisions/mail.md) | Eigener Mailserver, Mails, Bounces |
| [audit-log.md](decisions/audit-log.md) | Protokoll, Aufräum-Jobs |
| [admin-cli.md](decisions/admin-cli.md) | Befehle für den Betreiber |
| [containers-and-deployment.md](decisions/containers-and-deployment.md) | Container, Images, Netzwerke, Backup-Konzept |
| [ui.md](decisions/ui.md) | UI-Entscheidungen (Webserver, Sicherheit, Sprache) |
| [testing.md](decisions/testing.md) | Was wie getestet ist |

Weitere Dokumente: `NIST-800-63B-4.md` (Abgleich mit dem Standard), `OPEN-QUESTIONS.md`, `THIRD-PARTY.md` (Lizenzen), `DEPLOY.md`.

## Was noch auf Bestätigung wartet

Alle **[A]**-Punkte in den Themen-Dateien. Die größten:

- `JdbcClient` statt JPA (`backend-technology.md`).
- Die E-Mail gilt nur beim Setup-Link des ersten Admins als bestätigt (**[E]**, schon bestätigt).
- Eigene Sitzungstabelle und Cookie-Aufbau (`login-and-sessions.md`).
- Auslegung von "3 pro Stunde je E-Mail und IP" als getrennte Grenzen (`rate-limits.md`).
- Was "Ableitung" bei Kontextwörtern heißt (`passwords.md`).
- Bounces werden verworfen (`mail.md`, **[E]** wegen fehlendem Posteingang).
- Zeitzone "0 Uhr" für das Backup (`containers-and-deployment.md`).

## Bewusst noch nicht gebaut

- 2FA einrichten, neu erzeugen und abschalten für normale User: kommt mit Etappe 4, weil dort die Pflicht-2FA für Admin-Funktionen greift (siehe `OPEN-QUESTIONS.md`).
- Registrierung (Etappe 5), OIDC (Etappe 3), Rechte und Rollen mit Dashboard (Etappe 4), Hub-UI (Etappe 6).
- Backup-Container (Konzept steht, Umsetzung Etappe 7).
- Passwort-Reset durch Admins erzwingen (Etappe 4, bestätigt).
