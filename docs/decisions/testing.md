# Tests und Prüfungen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- 225 automatische Tests (Stand Etappe 4, 166 nach Etappe 3), darunter Rennen beim gleichzeitigen Einlösen eines Reset-Links, alle Limits, Sitzungslaufzeiten, Cookie-Eigenschaften, 2FA-Wiederverwendung, Neutralität der Antworten, Protokollschutz, Unicode. Der Mailversand läuft im Test über einen echten SMTP-Server (GreenMail).
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
