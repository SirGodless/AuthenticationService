# OIDC-Provider (Etappe 3)

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert. Der Code liegt in `src/main/java/net/anfall/auth/oidc/` (Protokoll, Schlüssel, Tokens) und `.../service/` (Dienste).

## Technik, gegen Quellen geprüft

- Spring Authorization Server steckt seit Spring Security 7.0 in Spring Security selbst. Version 7.1.2 (Boot 4.1.2). Starter: `spring-boot-starter-security-oauth2-authorization-server` (der Starter ohne "security" im Namen ist veraltet).
- **Geprüft in den Quelltexten von 7.1.2 und durch Tests** (die beiden [U]-Punkte aus der Spezifikation): OIDC ist standardmäßig aus und wird mit `server.oidc(...)` eingeschaltet. Der UserInfo-Endpunkt braucht einen `JwtDecoder`, den ich aus dem JWKS baue. Die Konfiguration läuft über `http.oauth2AuthorizationServer(...)`.
- **Eigene Filterkette** (Order 1) nur für die Provider-Adressen (`/oauth2/*`, `/.well-known/*`, `/connect/*`, `/userinfo`), die API-Kette (Order 2) bleibt wie sie ist. Das entspricht der Pfadverteilung im Caddy (Abschnitt 2).
- **Betrieb ohne Webserver:** Die Betreiber-Befehle (`auth-admin`) starten das Backend ohne Webserver. Die Provider-Bausteine, die auch dort gebraucht werden, stehen deshalb in `OidcBeansConfig`, die Filterkette nur in `OidcAuthorizationServerConfig` (nur Webbetrieb). Das ging beim ersten Versuch schief und wurde erst im echten Container bemerkt. Seitdem gibt es `NonWebContextTest`.

## Ablauf (Authorization-Code-Flow mit PKCE)

1. Der Dienst schickt den Browser an `/oauth2/authorize`.
2. Nicht angemeldet: Weiterleitung auf `/login?continue=<authorize-Adresse>`. Die Login-Seite folgt `continue` nach dem Login nur für Adressen, die mit `/oauth2/authorize?` beginnen (kein offener Redirect).
3. Angemeldet: Der `ServicePermissionFilter` prüft das Recht des Dienstes. **Fehlt es, gibt es keinen Code.** Statt den Browser mit einem Fehler zum Dienst zurückzuschicken, zeigt der Provider die Seite `/access-denied?service=<Name>` in der Sprache der Seite (**[E]** Abschnitt 6). Das wird protokolliert (`oidc.access_denied`).
4. Mit Recht: Code (2 Minuten gültig, einmalig) an die Rücksprungadresse.
5. Token-Endpunkt: Code gegen ID-Token, Access-Token und Refresh-Token tauschen. Clientanmeldung per `client_secret_basic` oder `client_secret_post`.

## Zugriffsentscheidung (am Provider, nicht im Hub)

- **Fail closed:** Ein Client ohne Dienst-Eintrag bekommt nie einen Code. Jeder Client verlangt ein Recht.
- Die Prüfung läuft **zweimal**: am Authorize-Endpunkt (kein Code ohne Recht) und im `OidcTokenCustomizer` bei **jedem** Token, also auch beim Code-Tausch und bei **jedem Refresh**. Der Customizer prüft: Konto nicht gebannt, Dienst vorhanden, Recht vorhanden, bei Sitzung ohne "Eingeloggt bleiben" nicht länger als 12 Stunden her. Schlägt etwas fehl, wird die Autorisierung **gelöscht** (der Refresh-Token ist endgültig tot) und der Token-Endpunkt antwortet `invalid_grant`. Ergebnis wird protokolliert (`oidc.token_denied` mit Grund).
- **Recht entzogen oder gebannt:** wirkt beim nächsten Refresh. Bereits ausgestellte Access-Tokens (10 Minuten) bleiben bis zum Ablauf gültig. **[U]** Wie schnell Vaultwarden und Audiobookshelf das bemerken, hängt von den Diensten ab (Abschnitt 6), darauf ist nicht gebaut.

## Tokens

- Access-Token: JWT, **10 Minuten** (einstellbar `app.oidc.access-token-ttl`). Refresh-Token: **30 Tage mit Rotation**, jeder Refresh-Token gilt nur einmal. Frist und Rotation einstellbar.
- **[A]** Ohne "Eingeloggt bleiben" endet die Sitzung nach **12 Stunden** (Abschnitt 3): Die Anmeldung trägt das Ende der Login-Sitzung als `SSO_END_<Sekunden>` bei sich, nach diesem Zeitpunkt lässt der Customizer keinen Refresh mehr zu. Mit "Eingeloggt bleiben" gilt die 30-Tage-Frist.
- **[A] ID-Token 30 Minuten** (Standard von Spring Authorization Server, nicht der Access-Token-Wert). Dienste prüfen das ID-Token nur beim Login.
- **[A] Refresh-Token auch ohne `offline_access`.** Spring stellt ihn für jeden Client mit Refresh-Grant aus. Ich habe das nicht eingeschränkt, damit Dienste, die den Scope nicht anfordern, nicht plötzlich keine Sitzung behalten. Festgehalten durch einen Test. Wenn du es strenger willst (nur mit `offline_access`), ist das eine kleine Änderung.
- Claims (**[E]** Abschnitt 6): `iss`, `sub` (= User-UUID), `aud`, `auth_time` immer. Mit Scope `email`: `email`, `email_verified`. Mit Scope `profile`: `preferred_username`, `locale` (Sprache des Kontos). Im ID-Token und über `/userinfo`. Eine Adresse gilt nur als bestätigt (`email_verified=true`), wenn sie es ist. Da sich nur Konten mit bestätigter Adresse anmelden können, ist das praktisch immer so.
- `auth_time`: In Spring Security 7 stammt der Wert aus einem `FactorGrantedAuthority` des Anmeldeobjekts. Ich setze dort die Anmeldezeit der Sitzung ein (Faktor `PASSWORD`, ob danach TOTP verlangt wurde, steht nicht im Token, also auch kein `amr`).

## Clients (Dienste)

- **[A]** Nur **vertrauliche** Clients mit Geheimnis (Vaultwarden und Audiobookshelf können das). Öffentliche Clients ohne Geheimnis sind nicht vorgesehen.
- **[A]** `client_id` = der Key des Dienstes (z. B. `vault`), das Recht heißt `service.<key>` und wird automatisch angelegt. Das Geheimnis hat 256 Bit und wird nur als Argon2-Hash gespeichert (ohne Pepper, es ist ein langes Zufallsgeheimnis), im Klartext nur einmal bei der Anlage angezeigt.
- **[A]** **PKCE ist für alle Clients Pflicht.** Ohne `code_challenge` gibt es keinen Code. **[A]** Keine Zustimmungsseite (eigene Dienste). Rücksprungadressen müssen exakt stimmen. Scopes: `openid`, `profile`, `email`, `offline_access`.
- Anlage bis zum Dashboard (Etappe 4) per Befehl: `auth-admin register-service`, Recht vergeben mit `auth-admin grant-service`.
- Nicht eingeschaltet: dynamische Client-Registrierung. Der Standard-Endpunkt `/connect/logout` (RP-initiated Logout) ist da, **beendet aber nicht** unsere Login-Sitzung, weil die keine Servlet-Sitzung ist. **[O]** Ob und wie ein Dienst die Sitzung im Hub beenden darf, ist nicht festgelegt, siehe `OPEN-QUESTIONS.md`.

## Signierschlüssel

- **RS256, RSA 2048** (**[A]**, kompatibelster Standard). Mehrere Schlüssel gleichzeitig im JWKS (Abschnitt 6, "rotierbar"), nur öffentliche Teile sichtbar.
- Private Schlüssel liegen **AES-256-GCM-verschlüsselt** in der Datenbank (`oidc_signing_keys`), Schlüssel `OIDC_KEY_ENCRYPTION_KEY` aus der Umgebung (ein eigener, dritter Schlüssel neben TOTP und Pepper, der Dienst startet nicht, wenn zwei gleich sind).
- **Rotation:** `auth-admin rotate-signing-key`. Der neue Schlüssel signiert sofort, der alte verliert **sofort** seinen privaten Teil und bleibt **2 Tage** im JWKS, damit noch gültige ID-Tokens prüfbar sind. Danach löscht ihn ein stündlicher Job. Getestet im echten Container.
- Beim ersten Start entsteht der erste Schlüssel automatisch.
- **Fund beim Bauen:** Spring weigert sich zu signieren, sobald nach einer Rotation zwei RSA-Schlüssel im JWKS stehen ("multiple keys for RS256"). Lösung: ein eigener `JwtEncoder`, der den einen Schlüssel mit privatem Teil nimmt.

## Widerruf

- `TokenRevocationService.revokeAll(userId)` löscht alle Autorisierungen eines Users. Aufgerufen bei **Bann** (`AccountBanService`), **Passwort-Reset** (Abschnitt 3: "beendet alle Sitzungen und Refresh-Tokens") und **`auth-admin reset-2fa`**.
- `AccountBanService`: beendet Sitzungen und Tokens. Niemand kann sich selbst bannen, und der **letzte nicht gebannte Admin** kann nicht gebannt werden (Abschnitt 7). Offene Einladungslinks werden mit Etappe 5 deaktiviert. Die Oberfläche kommt mit dem Dashboard (Etappe 4).

## Rechte (Vorstufe)

Für die Prüfung am Client gibt es schon `PermissionService.has` und die Tabellen `role_permissions` und `user_permissions` (nur boolesche Rechte, ein Eintrag heißt vergeben). Ein Recht hat, wer es direkt, über eine Rolle oder (bei `admin.*`) über die Rolle Admin hat. Zahlenrechte, Richtung, "unbegrenzt" und die Neuberechnung mit Konfliktfällen kommen mit Etappe 4 und ersetzen die Abfrage.

## Nicht gebaut / bewusst offen

- Hub als OIDC-Client: nicht nötig, siehe `hub-ui-authentication.md` (Annahme Variante 1).
- Verwaltung der Dienste im Dashboard (Etappe 4), Statusanzeige der Dienste (Etappe 6).
- Öffentliche Clients, Zustimmungsseite, dynamische Registrierung, Geräte-Flow.
