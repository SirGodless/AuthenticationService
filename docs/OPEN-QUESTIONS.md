# Offene Fragen an Joshua

Stand: Etappe 5 (18.11.2026).

## Erledigt (Antworten vom 18.11.2026)

- **`offline_access`:** Freigegeben, Refresh-Tokens gibt es nur noch mit diesem Scope (`decisions/oidc-provider.md`). Dienste müssen den Scope anfordern, wenn sie eine längere Sitzung wollen.
- **Hub-Anmeldung:** Variante 1 (Sitzungs-Cookie, CORS nur für `anfall.net`) bleibt die Annahme.

- **ID-Token 30 Minuten:** in Ordnung.
- **Dienste (Vaultwarden, Audiobookshelf, ...):** Die Anbindung ist vorerst zurückgestellt. Joshua sucht eine andere Lösung, weil sich die Dienste nicht gut genug einbinden lassen und die Oberflächen ohnehin angepasst werden müssen. Der Provider aus Etappe 3 bleibt, wie er ist. Es wird nichts für einzelne Dienste gebaut oder getestet, bis Joshua die neue Richtung nennt. Die Fragen zu Single Logout und Dienst-Einstellungen sind damit vertagt.

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

0. **Annahmen aus Etappe 5 zur Bestätigung** (Einzelheiten in `decisions/invite-links.md`):
   - Nicht gesetztes `links.max_active` heißt: kein Platz.
   - Der Link wird nur einmal angezeigt (gespeichert ist nur der Hash).
   - Die 30 Tage sind eine feste Obergrenze der einstellbaren Werte, eingestellt wird in Stunden und Tagen im Reiter Settings.
   - `admin.*` und die Rolle Admin lassen sich nie per Link vergeben.
   - Admins mit `admin.links.view_all` dürfen fremde Links deaktivieren, Grenzen ändern und IP-Sperren aufheben braucht `admin.settings.manage`.
   - Ein abgelehnter Link (Ersteller gebannt oder ohne Recht) wird nicht verändert und gilt wieder, wenn der Ersteller das Recht zurückbekommt.
   - Die IP-Sperre löst beim vierten falschen Link aus und sperrt den ganzen Link-Endpunkt (auch gültige Links) einen Tag.
   - Vorgriff: Die Seite `/account/links` gibt es schon jetzt, die Profilseite folgt mit Etappe 6.

1. **Annahmen aus Etappe 4 zur Bestätigung** (Einzelheiten in `decisions/permissions-and-roles.md`, `admin-dashboard.md`, `two-factor-settings.md`):
   - Neues Recht `admin.users.force_password_reset` (in der Startliste fehlte eines dafür), in der Rolle Admin enthalten.
   - Zahlenwerte von Rechten sind 0 oder größer.
   - Eigene Rechte: Schlüssel wie `media.upload`, `admin.` und `service.` reserviert, löschbar nur ohne Verwendung.
   - Eingebaute Rollen lassen sich nicht umbenennen oder löschen, der letzte Admin behält die Rolle Admin.
   - Jede 2FA-Änderung verlangt das Passwort und, falls schon eingerichtet, einen Code. Limit 5 Fehlversuche in 15 Minuten.
   - `/api/admin/overview` verlangt keine 2FA (nur Übersicht, damit die Seite den Hinweis zeigen kann).
   - Passwort-Reset erzwingen nur bei bestätigter E-Mail.
   - Admin-Seiten laufen gleich-origin unter `auth.anfall.net/admin`, Rückfragen mit Browser-Dialogen bis zum Design (Etappe 6).
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
