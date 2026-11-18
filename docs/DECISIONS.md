# Entscheidungen und Annahmen (Inhaltsverzeichnis)

Stand: nach Etappe 7 und dem Zusatz "Dienste anbinden" (18.11.2026). Die Einzelheiten stehen nach Fachthema in `docs/decisions/`.
Kennzeichnung wie in `CLAUDE.md`: **[E]** von Joshua entschieden, **[A]** Annahme (wartet auf Bestätigung), **[U]** nicht oder nicht ganz verifiziert.

## Themen

| Datei | Inhalt |
| :- | :- |
| [code-structure.md](decisions/code-structure.md) | Struktur beider Repos und warum, Regeln für Lesbarkeit |
| [backend-technology.md](decisions/backend-technology.md) | Versionen, Spring, Datenbankzugriff, Fehlercodes |
| [data-model.md](decisions/data-model.md) | Tabellen, Trennung personenbezogener Daten |
| [first-admin-setup.md](decisions/first-admin-setup.md) | Setup-Link, erster Admin, 2FA, Wiederherstellungscodes |
| [hub-ui-authentication.md](decisions/hub-ui-authentication.md) | Vorschlag: Wie sich die Hub-UI an der API anmeldet (Frage vor Etappe 3) |
| [permissions-and-roles.md](decisions/permissions-and-roles.md) | Rechte (boolesch und Zahl), Berechnung, Rollen als Live-Gruppen, wer was darf |
| [admin-dashboard.md](decisions/admin-dashboard.md) | Admin-Schnittstelle und -Oberfläche, Pflicht-2FA, Passwort-Reset erzwingen |
| [two-factor-settings.md](decisions/two-factor-settings.md) | 2FA einrichten, Codes erneuern, abschalten |
| [service-integration.md](decisions/service-integration.md) | Dienste im Dashboard anlegen, Dienst-Schnittstelle (Rechte abfragen, Farbe und Status melden), Namensraum der Rechte |
| [security-and-delivery.md](decisions/security-and-delivery.md) | Sicherheits-Header, CORS, Single Sign-on, Caddy-Block, Backup, feste Versionen, Darstellung als Kontoeinstellung |
| [hub-and-services.md](decisions/hub-and-services.md) | Gestaltung "Raster", Hub und Seitenmenü, Akzentfarbe pro Dienst, Statusanzeige, Profilseite |
| [invite-links.md](decisions/invite-links.md) | Einladungslinks, Registrierung, Gültigkeit, Prüfung des Erstellers, IP-Sperre |
| [oidc-provider.md](decisions/oidc-provider.md) | OIDC-Provider: Ablauf, Zugriffsentscheidung, Tokens, Clients, Signierschlüssel, Widerruf |
| [login-and-sessions.md](decisions/login-and-sessions.md) | Anmeldung, Sitzungen, Cookies, CSRF |
| [rate-limits.md](decisions/rate-limits.md) | Limits, echte Besucher-IP |
| [passwords.md](decisions/passwords.md) | Passwortregeln nach NIST, Sperrliste, Kontextwörter |
| [mail.md](decisions/mail.md) | Eigener Mailserver, Mails, Bounces |
| [audit-log.md](decisions/audit-log.md) | Protokoll, Aufräum-Jobs |
| [admin-cli.md](decisions/admin-cli.md) | Befehle für den Betreiber |
| [containers-and-deployment.md](decisions/containers-and-deployment.md) | Container, Images, Netzwerke, Backup-Konzept |
| [ui.md](decisions/ui.md) | UI-Entscheidungen (Webserver, Sicherheit, Sprache) |
| [testing.md](decisions/testing.md) | Was wie getestet ist |

Weitere Dokumente: `INTEGRATION.md` (Leitfaden für Dienste), `api/openapi.yaml` (Schnittstellenbeschreibung), `examples/service_client.py`, `NIST-800-63B-4.md` (Abgleich mit dem Standard), `OPEN-QUESTIONS.md`, `THIRD-PARTY.md` (Lizenzen), `DEPLOY.md`.

## Was noch auf Bestätigung wartet

Alle **[A]**-Punkte in den Themen-Dateien. Die größten:

- `JdbcClient` statt JPA (`backend-technology.md`).
- Die E-Mail gilt nur beim Setup-Link des ersten Admins als bestätigt (**[E]**, schon bestätigt).
- Eigene Sitzungstabelle und Cookie-Aufbau (`login-and-sessions.md`).
- Auslegung von "3 pro Stunde je E-Mail und IP" als getrennte Grenzen (`rate-limits.md`).
- Was "Ableitung" bei Kontextwörtern heißt (`passwords.md`).
- Bounces werden verworfen (`mail.md`, **[E]** wegen fehlendem Posteingang).
- Zeitzone "0 Uhr" für das Backup (`containers-and-deployment.md`).
- OIDC (`oidc-provider.md`): ID-Token 30 Minuten (bestätigt), nur vertrauliche Clients, PKCE und keine Zustimmungsseite für alle, RS256 mit RSA 2048, `client_id` = Dienst-Key.
- Etappe 4 (`permissions-and-roles.md`, `admin-dashboard.md`, `two-factor-settings.md`): neues Recht `admin.users.force_password_reset`, Zahlenwerte nur 0 oder größer, Schlüsselregeln für eigene Rechte (`admin.` und `service.` reserviert), Passwort und Code bei jeder 2FA-Änderung, Übersicht `/api/admin/overview` ohne 2FA-Pflicht, Admin-Seiten gleich-origin, Browser-Standarddialoge bis zum Design.
- Etappe 5 (`invite-links.md`): nicht gesetztes `links.max_active` heißt kein Platz, Token nur einmal sichtbar, 30 Tage als feste Obergrenze der einstellbaren Werte, `admin.*` und die Rolle Admin nie per Link, Admins deaktivieren fremde Links mit `admin.links.view_all`, Aufheben der IP-Sperre und Grenzen mit `admin.settings.manage`, abgelehnter Link bleibt unverändert, Seite `/account/links` schon vor der Profilseite.
- Etappe 6 (`hub-and-services.md`): Schriftfarbe auf Dienstknöpfen automatisch, offline markierte Dienste bleiben öffenbar, Status = Antwort 2xx oder 3xx in 5 Sekunden, Mailserver-Status als Erreichbarkeitsprüfung nur für Admins, Dienst-Anlage weiter per Befehl.
- Etappe 7 (`security-and-delivery.md`): Backup-Container mit eigenem Zeitplan (Zeitzone Europe/Berlin), Schlüssel und DKIM im Backup, Hub nur unter `auth.anfall.net` (anfall.net leitet um, kein CORS), Basis-Images per Digest festgenagelt, `anfall.net`-Umleitung dauerhaft (301).
- Dienste anbinden (`service-integration.md`): Meldeweg darf nur Farbe und Status (nicht Name, Beschreibung, Adresse), Dienst-Token per client_credentials mit Merkmal `token_use=service`, Rücksprung-Adressen mit App-Schemas erlaubt, 600 Anfragen pro Minute und Dienst.

## Bewusst noch nicht gebaut

- Back-Channel-Logout, Einbetten der Dienste.
- Backup-Container (Konzept steht, Umsetzung Etappe 7).
