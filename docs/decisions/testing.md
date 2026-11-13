# Tests und Prüfungen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- 166 automatische Tests (Stand Etappe 3), darunter Rennen beim gleichzeitigen Einlösen eines Reset-Links, alle Limits, Sitzungslaufzeiten, Cookie-Eigenschaften, 2FA-Wiederverwendung, Neutralität der Antworten, Protokollschutz, Unicode. Der Mailversand läuft im Test über einen echten SMTP-Server (GreenMail).
- Ende-zu-Ende im Browser (`tests/e2e/run.sh`, mit `tests/check-i18n.js` im UserHub-Repo für die Sprachdateien) mit Test-Caddy über HTTPS: Setup, Login mit 2FA, falscher Code, "Passwort vergessen" mit Mail aus Mailpit, Sperrlisten-Fehler, Reset, Wiederherstellungscode, Abmelden, Cookie-Flags im echten Browser.
- Lizenzen: siehe `docs/THIRD-PARTY.md`.


## Etappe 3 (OIDC)

- Ein Test-Client (`testsupport/OidcTestClient`) durchläuft den Ablauf mit PKCE: Login-Weiterleitung mit Rücksprung, Code, Token-Austausch, Signaturprüfung gegen das JWKS, UserInfo, Refresh mit Rotation. Geprüft sind: ohne Recht kein Code (und keine Autorisierung in der Datenbank), Recht über Rolle, Rolle Admin gibt keinen Dienstzugang, Client ohne Dienst wird abgelehnt, PKCE Pflicht, falsche Rücksprungadresse (kein Redirect), falsches Geheimnis, Code nur einmal.
- Entzug des Rechts, Bann, Passwort-Reset und Ablauf der Sitzung (12 Stunden, im Test auf 2 Sekunden gesetzt) wirken beim Refresh. Mit "Eingeloggt bleiben" gilt die Grenze nicht.
- Schlüsselrotation: alter Schlüssel prüft weiter, neue Tokens nutzen den neuen, der alte verliert den privaten Teil, Löschung nach der Frist.
- Im Browser-Test (`tests/e2e/run.sh`) zusätzlich der ganze Ablauf gegen den echten Stack mit Test-Caddy: Dienst per `auth-admin` im Container anlegen, Login über die UI mit Rücksprung, Token-Austausch, Rotation per `auth-admin`, Seite "Kein Zugang" auf Englisch und Deutsch.
- Nicht prüfbar in der Sitzung: echte Dienste (Vaultwarden, Audiobookshelf). Das testet Joshua nach dem Deployment.
