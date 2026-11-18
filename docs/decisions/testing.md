# Tests und Prüfungen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- 338 automatische Tests (Stand nach dem Zusatz "Dienste anbinden", 307 nach Etappe 7, 291 nach Etappe 6, 272 nach Etappe 5, 225 nach Etappe 4, 166 nach Etappe 3), darunter Rennen beim gleichzeitigen Einlösen eines Reset-Links, alle Limits, Sitzungslaufzeiten, Cookie-Eigenschaften, 2FA-Wiederverwendung, Neutralität der Antworten, Protokollschutz, Unicode. Der Mailversand läuft im Test über einen echten SMTP-Server (GreenMail).
- Ende-zu-Ende im Browser (`tests/e2e/run.sh`, mit `tests/check-i18n.js` im UserHub-Repo für die Sprachdateien) mit Test-Caddy über HTTPS: Setup, Login mit 2FA, falscher Code, "Passwort vergessen" mit Mail aus Mailpit, Sperrlisten-Fehler, Reset, Wiederherstellungscode, Abmelden, Cookie-Flags im echten Browser.
- Lizenzen: siehe `docs/THIRD-PARTY.md`.


## Etappe 3 (OIDC)

- Ein Test-Client (`testsupport/OidcTestClient`) durchläuft den Ablauf mit PKCE: Login-Weiterleitung mit Rücksprung, Code, Token-Austausch, Signaturprüfung gegen das JWKS, UserInfo, Refresh mit Rotation. Geprüft sind: ohne Recht kein Code (und keine Autorisierung in der Datenbank), Recht über Rolle, Rolle Admin gibt keinen Dienstzugang, Client ohne Dienst wird abgelehnt, PKCE Pflicht, falsche Rücksprungadresse (kein Redirect), falsches Geheimnis, Code nur einmal.
- Entzug des Rechts, Bann, Passwort-Reset und Ablauf der Sitzung (12 Stunden, im Test auf 2 Sekunden gesetzt) wirken beim Refresh. Mit "Eingeloggt bleiben" gilt die Grenze nicht.
- Schlüsselrotation: alter Schlüssel prüft weiter, neue Tokens nutzen den neuen, der alte verliert den privaten Teil, Löschung nach der Frist.
- Im Browser-Test (`tests/e2e/run.sh`) zusätzlich der ganze Ablauf gegen den echten Stack mit Test-Caddy: Dienst per `auth-admin` im Container anlegen, Login über die UI mit Rücksprung, Token-Austausch, Rotation per `auth-admin`, Seite "Kein Zugang" auf Englisch und Deutsch.
- Nicht prüfbar in der Sitzung: echte Dienste (Vaultwarden, Audiobookshelf). Das testet Joshua nach dem Deployment.

## Etappe 4 (Rechte, Rollen, Dashboard, 2FA)

- **Konfliktfälle der Rechteberechnung** (`EffectivePermissionCalculatorTest`, ohne Datenbank): boolesch aus einer von mehreren Quellen, höher und niedriger, nicht gesetzt gegen 0, unbegrenzt gegen Zahlen, unbegrenzt bei "niedriger = mehr" oder nicht erlaubt (wird ignoriert), implizites Recht der Rolle Admin.
- **Admin-API mit eingeloggtem Admin** (Hilfe `testsupport/AdminTestBase`: Admin mit Rolle und 2FA, oder User mit einzelnen Rechten, mit und ohne 2FA): `AdminAccessGuardTest` (401, `forbidden`, `two_factor_required`, Reiter-Übersicht), `RoleAdminApiTest` (Live-Gruppen, Zahlenwerte aus mehreren Rollen, Rolle Admin geschützt, letzter Admin), `PermissionAdminApiTest` (Schlüssel, Typ und Richtung, Löschen, direkte Rechte), `UserAdminApiTest` (Suche, Bann, 2FA zurücksetzen, Passwort-Reset erzwingen samt Mail und Link), `AuditAdminApiTest` (Recht, Filter, Blättern), `TwoFactorSettingsTest`.
- Refresh-Token nur mit `offline_access` (`OidcAuthorizationCodeFlowTest`).
- **Browser-Test** (`tests/e2e/run.sh`) erweitert: Umleitung von `/admin` zum Login und zurück, Reiter in beiden Sprachen, Benutzerdetail, Rolle und Recht anlegen, reservierter Schlüssel abgelehnt, Protokoll, 2FA-Seite (Status, Pflicht-Hinweis, neue Codes). Im Test gefunden und behoben: Der Datensatz der Rechteliste hat eine andere Form als erwartet, und eine spät fertige Ansicht überschrieb einen neueren Reiter (siehe `admin-dashboard.md`).
- `tests/check-i18n.js` (UserHub) prüft jetzt auch die Module, die ein Seitenskript lädt, und fest benannte Textschlüssel darin.
- Nicht automatisch geprüft: das Aussehen (Etappe 6) und das Banden einer anderen Person im Browser (es gibt bis Etappe 5 keinen zweiten Account ohne Einladung, die API-Tests decken Bann ab).

## Etappe 5 (Einladungslinks)

- `InviteLinkCreationTest` (18): Recht, Platzlimit (auch frei werden durch Benutzen, Deaktivieren, Ablauf, "unbegrenzt", **Rennen** zweier gleichzeitiger Erstellungen um den letzten Platz), Gültigkeit (Standard, Minimum, Maximum, kürzer mit Rechten), was vergeben werden darf (nicht gehalten, nicht link-vergebbar, `admin.*`, Zahlenwerte, Rollen), nur der Hash gespeichert, Liste, Deaktivieren.
- `InviteRegistrationTest` (16): Konto und Mail, Sprache, Öffnen verbraucht nicht, Formularfehler verbrauchen nicht, Passwortregeln, neutrale Antworten für unbekannt, abgelaufen, benutzt und deaktiviert, Rolle und Rechte vergeben und protokolliert, **Prüfung des Erstellers beim Einlösen** (gebannt, Recht verloren, Rolle bekam ein fremdes Recht, Rolle gelöscht), Bann deaktiviert offene Links und lässt Konten bestehen, **Rennen** mit 2 und mit 8 parallelen Anfragen.
- `InviteIpBlockTest` (8): vierter falscher Link sperrt, drei nicht, nur Link-Endpunkt und nur diese Adresse, abgelaufene, benutzte und deaktivierte Links zählen nicht, IPv6 pro /64, Absenden zählt wie Öffnen, Admin hebt auf.
- `InviteLimitsAdminTest` (5): Standardwerte, geänderte Grenzen wirken, Obergrenze 30 Tage und Plausibilität, Recht und 2FA, Admin-Liste und Deaktivieren.
- Browser-Test: Rechte im Dashboard vergeben, Link auf `/account/links` erstellen, Registrierung auf einer frischen Browsersitzung (Formularfehler, Konto, benutzter Link, Mail, Bestätigung, Login), IP-Sperre und Aufheben im Dashboard.

## Etappe 6 (Hub und Dienste)

- `ServiceAdminApiTest` (5): Farbe und Anzeige-Daten ändern (klein gespeichert), leere Farbe, ungültige Farben und Adressen werden abgelehnt (`red`, `#fff`, `#12345g`, `javascript:` ...), Recht und 2FA, Reiter in der Übersicht.
- `ServiceHealthTest` (5): lokaler Testserver, aktiv/inaktiv/Weiterleitung/unbekannt, Dienst fällt aus und kommt zurück, Mailserver.
- `HubApiTest` (7): nur Dienste mit Recht, live berechnet, Rolle Admin gibt keine Dienste, Status je Dienst, Mailstatus nur für Admins, Konto-Angaben.
- `LanguageTest` (2).
- Browser-Test erweitert: Farbe im Dashboard einstellen, im Hub die **berechnete** Knopffarbe prüfen (`rgb(229, 160, 13)` mit lesbarer Schrift), Seitenmenü, nur berechtigte Dienste, Hell/Dunkel bleibt nach dem Neuladen, Sprache des Kontos auf der Profilseite, Startseite ohne Anmeldung führt zum Login. Alle Seiten laufen ohne CSP-Verstöße.
- `tests/check-i18n.js` kennt die neuen Themen. Nicht automatisch geprüft: das Aussehen. Es wurde mit Beispieldaten in beiden Darstellungen angesehen.

## Etappe 7 (Absicherung und Auslieferung)

- `SingleSignOnTest` (4): Dienst direkt aufgerufen, einmal anmelden und ein zweiter Dienst gibt sofort einen Code, Abmelden beendet es, das Recht wird je Dienst geprüft, ein gebannter User bekommt von keinem Dienst einen Code.
- `ResponseHeadersTest` (4): `nosniff`, `X-Frame-Options`, `no-store`, **kein CORS** (normale Anfrage und Preflight, 6 Pfade), Cookie host-only ohne Domain.
- `AccountSettingsTest` und `HubApiTest` erweitert: Darstellung als Kontoeinstellung, gemeldete Farbe überschreibt die Hub-Farbe, außer sie ist festgehalten.
- **`backup/test.sh`:** Zeitplan, Aufbewahrung (400 nachgespielte Tage: 7 tägliche, 12 monatliche, der älteste tägliche von vor 6 Tagen), Dateirechte, Schlüsseldatei und Wiederherstellung mit einer Wegwerf-Datenbank.
- **Browser-Test erweitert** (103 Prüfungen): Der Test-Caddy wird aus `deploy/Caddyfile.snippet` gebaut (HSTS kommt von dort). Header aller 11 Seitenarten, API ohne CORS, kein Umschalter für die Darstellung auf den Seiten, Einstellung im Profil wirkt und bleibt, Farbe vom Dienst überschreibt, Kästchen hält die Hub-Farbe, Backup-Container im echten Stack (Dump, Schlüssel, DKIM, gesund).
- Nicht prüfbar in der Sitzung: echte Dienste, echter Mailversand, echte Domains und Zertifikate, Zustellung an große Mail-Anbieter, die Schwachstellenprüfung der Abhängigkeiten (Datenbanken aus der Umgebung nicht erreichbar).

## Zusatz "Dienste anbinden"

- `ServiceDashboardTest` (9): Dienst anlegen mit Recht und Client, Geheimnis einmal und nur als Hash, Pflichtfelder, ungültige Eingaben legen nichts an, strenge Regeln für Rücksprung-Adressen (10 Fälle), Schlüssel eindeutig, Adressen ersetzen, neues Geheimnis macht das alte ungültig, Recht und 2FA, Spalte "Dienst" bei den Rechten.
- `ServiceApiTest` (22): Token per client_credentials (Signatur gegen JWKS, kein Refresh-Token), falsches Geheimnis und Scope, 401 ohne und mit kaputtem Token, **Nutzer-Token werden abgelehnt** (auch mit Scope `service`), nur Rechte des eigenen Namensraums, Zahlenwerte aus mehreren Quellen, `unlimited`, 0 ist ein Wert, Präfix engt ein und verlässt den Namensraum nie, fremder Dienst sieht nur seine Rechte, kein Zugang und Bann, Entzug wirkt sofort, ungültige `sub`, eigene Daten, gemeldete Farbe überschreibt außer festgehalten, ungültige Meldungen ändern nichts, gemeldeter Status mit Vorrang, Protokoll, Begrenzung, Token ohne Rechte darin.
- Browser-Test (116 Prüfungen) und das Python-Beispiel gegen den echten Stack. OpenAPI-Datei mit `openapi-spec-validator` geprüft.
