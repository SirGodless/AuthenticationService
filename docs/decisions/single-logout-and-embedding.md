# Gemeinsames Abmelden, Einbetten und Nutzerabfrage (Etappe 8, 19.11.2026)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

Anlass: Der erste eigene Dienst (SubSplit) soll auf Konten aus dem Hub umgestellt werden. Joshua (19.11.2026) hat dafür drei Vorgaben gemacht, die der Hub vorher können muss. Die Spezifikation für Dienste steht in `docs/INTEGRATION.md`.

## Entscheidungen von Joshua

- **[E] Zwei Aufrufarten.** Jeder Dienst ist im Hub eingebettet und als eigene Webseite unter eigener Subdomain nutzbar. Der Dienst darf für den Rahmen eine eigene Ansicht liefern, das muss in der Spezifikation stehen (`INTEGRATION.md`, Abschnitt 11).
- **[E] Abmelden im Hub meldet überall ab** (wie bei Google). Ersetzt die Entscheidung vom 18.11.2026.
- **[E] Dienste kennen von Nutzern nur die UUID** und speichern daran ihre eigenen Daten. Name, E-Mail, Sprache verwaltet der Hub und liefert sie auf Abfrage. Brauchen später mehrere Dienste dieselben Daten, wird entschieden, ob sie in den Hub oder anderswohin kommen.

## Gemeinsames Abmelden (**[A]** Umsetzung)

- **Verfahren:** OpenID Connect Back-Channel Logout. Spring Authorization Server 7.1.2 bringt dafür nichts mit (nur das RP-initiated Logout `end_session`, das mit unseren eigenen Sitzungen nicht zusammenspielt). Gebaut ist es in `oidc/BackchannelLogoutService`. In den Quelltexten des Jars gibt es keine Back-Channel-Klassen (geprüft).
- **Sitzungskennung `sid`:** Jede Hub-Sitzung bekommt in `login_sessions` eine zufällige UUID (V22). Sie wird als Recht `SID_<uuid>` in den Principal geschrieben, mit der Autorisierung gespeichert und als Claim `sid` ins ID-Token gesetzt. Sie ist weder das Cookie noch dessen Hash.
- **Abmelden im Hub** beendet die Autorisierungen, die zu **dieser** Hub-Sitzung gehören (Suche per `LIKE` auf die gespeicherten Attribute, bei dieser kleinen Nutzerzahl unkritisch), und schickt den betroffenen Diensten ein Logout-Token mit `sid`. Andere Geräte des Nutzers bleiben angemeldet (so wie bei Google).
- **Bann, Passwort-Reset, 2FA-Reset, erzwungener Reset** laufen über `TokenRevocationService.revokeAll` und beenden **alle** Sitzungen: Logout-Token ohne `sid`.
- **Zustellung:** nach dem Commit, im Hintergrund (virtuelle Threads), einmal, 3 Sekunden Verbindungs- und 5 Sekunden Antwortzeit, keine Weiterleitungen, keine Wiederholung. Erfolg ist 200 oder 204. Jeder Versuch steht im Protokoll (`service.backchannel_logout`, mit `ok`, `status` oder `error`). Gewollt einfach: Die Refresh-Tokens sind schon gelöscht, die zweite Absicherung greift also immer.
- **Logout-Token:** RS256 mit demselben Schlüssel wie die anderen Tokens, Kopf `typ: logout+jwt`, Claims `iss`, `sub`, `aud`, `iat`, `exp` (+120 s), `jti`, `events`, optional `sid`, kein `nonce`.
- **Discovery:** `backchannel_logout_supported` und `backchannel_logout_session_supported` sind `true`.
- **Dashboard:** pro Dienst eine Logout-Adresse (`services.backchannel_logout_uri`, V22), wie die anderen Adressen nur `http` oder `https`. Leer heißt: keine Meldung.
- **Nicht gebaut:** Abmelden vom Dienst aus für alle Dienste (RP-initiated Logout). Meldet sich ein Nutzer im Dienst ab, endet nur dessen Sitzung.
- **[U] Spezifikation nicht gelesen.** `openid.net` war in der Sitzung nicht erreichbar. Format, Claims, `typ` und Antwortcodes wurden gegen die Referenzimplementierung `node-oidc-provider` 9.15.0 (npm) abgeglichen, nicht gegen den Spezifikationstext. Tests: `BackchannelLogoutTest` (7 Fälle gegen einen lokalen Webserver als Logout-Adresse, inklusive Signaturprüfung des Tokens). **Vor dem produktiven Einsatz bitte die Spezifikation gegenlesen.**

## Einbetten (**[A]**)

- **Öffnungsmodus** `embedded` ist im Dashboard pro Dienst einstellbar (er stand schon im Datenmodell), dazu ist die Adresse zum Öffnen Pflicht. Im Hub öffnet der Dienst dann unter `/app/<key>` (Seitenmenü des Hubs plus Rahmen), alle anderen im neuen Tab.
- **Rahmen:** `sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-downloads"`, `referrerpolicy="no-referrer"`. Ohne `allow-top-navigation` kann der Dienst den Hub nicht wegnavigieren. `allow-same-origin` gilt für den Ursprung des Dienstes, das ist eine andere Adresse als die des Hubs (bei gleichem Ursprung wäre die Kombination mit `allow-scripts` laut MDN wirkungslos).
- **Header:** Nur die Seite `/app/<key>` bekommt `frame-src https:` (eigene Datei `nginx/security-headers-embed.conf`), sonst gilt überall `default-src 'none'`. Der Hub selbst bleibt mit `frame-ancestors 'none'` und `X-Frame-Options: DENY` nicht einbettbar. Ob ein Dienst sich einbetten lässt, entscheidet er mit seinem eigenen `frame-ancestors`.
- **Eigene Ansicht für den Rahmen:** Der Dienst erkennt den Rahmen am Header `Sec-Fetch-Dest: iframe`. Ich habe das einem Parameter wie `?embedded=1` vorgezogen, weil der Parameter bei den Weiterleitungen der Anmeldung verloren ginge und jeder Link im Dienst ihn mitführen müsste. Der Header kommt bei jeder Navigation im Rahmen automatisch. Er beginnt mit `Sec-` und kann von JavaScript nicht gesetzt werden (MDN, "Forbidden request header: Yes").
- **Cookies im Rahmen:** Hub (`anfall.net`) und Dienste (`<name>.anfall.net`) sind dieselbe "Site" (registrierbare Domain). Cookies mit `SameSite=Lax` oder `Strict` gelten dort nicht als Drittanbieter-Cookies und werden im Rahmen mitgeschickt (MDN, Cookies-Leitfaden). **Folge: Eingebettete Dienste müssen unter einer Subdomain von `anfall.net` laufen.** Das steht in der Spezifikation.
- **Geprüft** über die MDN-Texte im GitHub-Repo `mdn/content` (die Webseite selbst war nicht erreichbar): `Sec-Fetch-Dest` (Wert `iframe`), `frame-ancestors`, `frame-src`, `sandbox`, `SameSite`. **[U]** Das Verhalten in echten Browsern mit einem echten Dienst ist nicht getestet, auch nicht die Mindestversionen der Browser für `Sec-Fetch-Dest`.
- **Grenze:** Zeigt der Hub eine eigene Seite im Rahmen (zum Beispiel "Kein Zugang"), bleibt der Rahmen leer. Das trifft nur Nutzer, die das Recht gerade verloren haben. Eine eigene Meldung dafür gibt es nicht. **[O]** Nachrichten zwischen Hub und Dienst (`postMessage` für Titel, Höhe, Navigation) sind nicht festgelegt.
- **Test:** `tests/e2e/setup-flow.js` (Menülink und Knopf führen nach `/app/e2e`, Rahmenattribute, Header, Dienst im neuen Tab wird nicht geladen).

## Nutzerabfrage für Dienste (**[A]**)

- `GET /api/service/users` (nur mit Dienst-Token). Ohne `sub`: alle Nutzer mit Zugang zum Dienst. Mit `sub` (mehrfach, höchstens 100): genau diese, auch ohne Zugang oder gesperrt, damit ein Dienst Namen in alten Daten anzeigen kann. Antwort pro Nutzer: `sub`, `username`, `email`, `emailVerified`, `locale`, `banned`, `access`.
- **Datenschutz:** Ein Dienst kann nur Nutzer abfragen, deren UUID er kennt, oder die mit Zugang zu ihm. Die UUID ist nicht erratbar. Einen Durchlauf über alle Konten des Hubs gibt es nicht. Die E-Mail-Adresse ist enthalten, weil Dienste Mails selbst verschicken (SubSplit: Erinnerungen). Das ist eine Annahme von mir, bitte bestätigen.
- **Anzeigename:** Der Hub hat nur den Username. Er dient vorerst als Anzeigename in Diensten. Ein eigener Anzeigename im Hub wäre eine neue Kontoeigenschaft und ist **[O]**.
- Der Dienst soll die Daten nicht dauerhaft speichern, nur kurz zwischenspeichern (steht in der Spezifikation).
- Tests: drei neue in `ServiceApiTest`, Python-Beispiel `docs/examples/service_client.py` (`users`, `verify_logout_token`; letzteres mit einem Wegwerf-Schlüssel getestet, `users` nicht gegen einen echten Stack).
