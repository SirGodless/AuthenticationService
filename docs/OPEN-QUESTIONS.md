# Offene Fragen an Joshua

Stand: Etappe 7 (18.11.2026). Alle sieben Etappen sind gebaut.

## Überblick: Was noch offen ist

**Vor dem Echtbetrieb zu tun (von dir, auf dem Server):**

1. `docs/DEPLOY.md` durchgehen (Ordner für Backups, `.env`, Caddy-Block, Mail-DNS).
2. Die [A]-Annahmen unten bestätigen oder korrigieren (je Etappe eine Liste).
3. **Schwachstellenprüfung der Abhängigkeiten** (**[U]**): In der Entwicklungsumgebung nicht möglich. Vor dem Release z. B. `mvn org.owasp:dependency-check-maven:check` laufen lassen. Aufgefallen: Tomcat 11.0.27 (neuer ist 11.0.29).
4. Eigene Dienste nach `docs/INTEGRATION.md` bauen (die Schnittstellen stehen, Dienste legst du im Dashboard an). Ob fremde Dienste wie Vaultwarden, Audiobookshelf und Pelican sich wie am Provider getestet verhalten, geht nur auf dem Server.

**Bewusst offen oder nicht gebaut (**[O]**):** Back-Channel-Logout (Abmelden im Hub beendet die Sitzung beim Provider, nicht die der Dienste, die lösen das über kurze Sitzungen und Refresh), Einbetten der Dienste, Passkeys, Löschfunktion für Konten, E-Mail-Änderung durch User, Pelican-Anbindung, Hub zusätzlich unter `anfall.net` (nur mit CORS, falls du es später willst).

**Nicht prüfbar in der Entwicklung (**[U]**):** echter Mailversand und Zustellung an Gmail oder Outlook, Port 25 ausgehend, rDNS, echte Domains und Zertifikate, echte Dienste, Verhalten der Dienste bei Sperren (wie schnell ein Bann dort wirkt), Argon2-Dauer auf dem Zielsystem (Log beim Start).

## Erledigt (Antworten vom 18.11.2026)

- **`offline_access`:** Freigegeben, Refresh-Tokens gibt es nur noch mit diesem Scope (`decisions/oidc-provider.md`). Dienste müssen den Scope anfordern, wenn sie eine längere Sitzung wollen.
- **Hub-Adresse:** Hub, Login und OIDC laufen alle auf `anfall.net` (kein eigener Auth-Host, auf `anfall.net` läuft sonst nichts und es ist nichts Öffentliches geplant). `www` leitet um, CORS wird nicht gebraucht.
- **Darstellung (hell, dunkel):** als Einstellung im Konto, nicht auf jeder Seite.
- **Farbe von Diensten:** Eine vom Dienst gemeldete Farbe überschreibt die Hub-Farbe, außer ein Kästchen im Hub hält sie fest.
- **Single Sign-on:** Wer angemeldet ist, kommt ohne neue Eingabe in jeden Dienst mit Recht. Dienste bleiben direkt aufrufbar. Umgesetzt und getestet am Provider.

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

0000. **Annahmen zu "Dienste anbinden"** (Einzelheiten in `decisions/service-integration.md`): Ein Dienst darf nur Farbe und Status melden (nicht Name, Beschreibung, Adresse). Rücksprung-Adressen dürfen auch App-Schemas wie `audiobookshelf://oauth` sein, `http` nur für localhost. Der Dienst wird bei Hub-Abmeldung nicht benachrichtigt. Ein vor dieser Änderung angelegter Dienst muss neu angelegt werden (noch nichts deployt, deshalb ohne Nacharbeit).

000. **Annahmen aus Etappe 7 zur Bestätigung** (Einzelheiten in `decisions/security-and-delivery.md`):
   - Das Backup läuft als eigener Container mit eigener Zeitplan-Schleife (nicht Cron). Im Backup liegen Dump, die beiden Schlüssel (TOTP, OIDC) und der DKIM-Schlüssel, nicht der Pepper. **Hinweis:** Dump und Schlüssel liegen damit zusammen, wer den Ordner lesen kann, kann TOTP-Geheimnisse entschlüsseln. Behandle `backups/` wie die `.env`.
   - Der Mailserver-Status im Hub ist eine Erreichbarkeitsprüfung, kein Zustelltest.
   - Vor dem Login (ohne Konto) gilt die Darstellung des Geräts, bis jemand sich auf diesem Browser angemeldet hat.
   - `www.anfall.net` leitet dauerhaft (301) auf `anfall.net` um. HSTS ohne `includeSubDomains`.

00. **Annahmen aus Etappe 6 zur Bestätigung** (Einzelheiten in `decisions/hub-and-services.md`):
   - Schriftfarbe auf Dienstknöpfen wird automatisch Schwarz oder Weiß, ein offline markierter Dienst bleibt öffenbar (umrandeter Knopf).
   - Status: Antwort 2xx oder 3xx innerhalb von 5 Sekunden, Prüfung alle 60 Sekunden, Hub holt alle 30 Sekunden neu.
   - Der Mailserver-Status ist eine Erreichbarkeitsprüfung (Verbindung zum Mailport), kein Zustelltest, und nur für Admins sichtbar.
   - Dienste werden weiter per `auth-admin register-service` angelegt, das Dashboard ändert nur Anzeige, Adressen und Farbe.
   - Die Meldung der Farbe durch einen Dienst ist vorbereitet (Spalten und Kästchen), einen Meldeweg gibt es noch nicht.

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
   - Admin-Seiten laufen unter derselben Adresse wie die API (`anfall.net/admin`), Rückfragen mit Browser-Dialogen bis zum Design (Etappe 6).
2. **Zeitzone für "0 Uhr" beim Backup:** Umgesetzt mit Europe/Berlin (`BACKUP_TZ` in `.env`). Sag Bescheid, wenn es UTC sein soll.

## Nicht verifiziert

- **[U]** Auslieferung der Mails an Gmail, Outlook und andere sowie die Spam-Bewertung (neue IP ohne Ruf). Nicht prüfbar in der Cloud-Sitzung.
- **[U]** Dass Port 25 auf deinem Server ausgehend wirklich offen ist (Prüfbefehl in der README).
- **[U]** Name des Menüpunkts für Reverse DNS in der Hetzner-Oberfläche.
- **[U]** Quelle und Nutzungsbedingungen der NCSC-Liste selbst (SecLists steht unter MIT, die Liste stammt vom UK NCSC).
- **[U]** `pages.nist.gov` war nicht erreichbar, der NIST-Text stammt aus dem offiziellen GitHub-Repo von NIST.
- **[U]** `caddy_net` und `/opt/containers/` aus deinen Infrastruktur-Notizen (das Subnetz ist bestätigt).
- **[U]** Docker-Build auf deinem Server (ich konnte ihn nur mit einem Umweg für das Proxy-Zertifikat der Sitzung testen).
- **[U]** Dauer eines Argon2-Hashs auf deinem Zielsystem (Log beim Start prüfen).
