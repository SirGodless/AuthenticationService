# Wie sich die Hub-UI an der API anmeldet

Kennzeichnung: **[A]** Variante 1 ist die Annahme, Joshua hat (13.11.2026) keine Variante gewählt, sondern um eine ehrliche Empfehlung gebeten. Sie wartet auf Bestätigung. Gefragt wurde vor Etappe 3, weil `CLAUDE.md` (Abschnitt 8) das so verlangt. Etappe 3 hängt nicht davon ab.

## Ausgangslage

- Der Hub (Sidebar mit Diensten, später Admin-Dashboard und Profil) läuft als statische Seiten auf `anfall.net` (**[E]**).
- Login, Registrierung und API laufen auf `auth.anfall.net` (**[E]**). Das Sitzungs-Cookie `__Host-sid` gilt nur dort (host-only, **[A]** aus Abschnitt 10).
- Die UI trifft keine Rechteentscheidung, das Backend entscheidet bei jedem Aufruf (**[E]**). Keine Tokens in `localStorage`, falls vermeidbar (Abschnitt 10).
- `anfall.net` und `auth.anfall.net` sind **derselbe "Site"** (gleiche registrierbare Domain). Das ist der Punkt, auf dem Variante 1 beruht.

## Variante 1 (Vorschlag): Sitzungs-Cookie von `auth.anfall.net`, API-Aufrufe aus dem Hub mit CORS

Der Hub meldet nicht selbst an. Ohne Sitzung leitet er auf `https://auth.anfall.net/login?return=https://anfall.net/...` weiter, nach dem Login geht es zurück. Danach ruft die Hub-JavaScript die API auf `auth.anfall.net` mit `fetch(..., { credentials: "include" })` auf. Der Browser schickt das Sitzungs-Cookie mit, weil die Anfrage "same-site" ist (SameSite=Lax erlaubt das).

- **Backend:** CORS nur für die Origin `https://anfall.net` (und `https://www.anfall.net`), mit Credentials. Der CSRF-Token (Cookie `XSRF-TOKEN` ist für den Hub nicht lesbar, andere Origin) kommt zusätzlich über `GET /api/auth/csrf` als JSON, das nur die erlaubte Origin lesen darf. Der Rücksprung `return` wird gegen eine feste Liste erlaubter Adressen geprüft (kein offener Redirect).
- **Eine** Sitzung, **ein** Login, **kein** Token im Browser-JavaScript. Das Cookie bleibt `HttpOnly` und host-only.
- Gegen eine fremde Subdomain (z. B. `vault.anfall.net`, falls dort etwas kompromittiert würde): Sie ist ebenfalls "same-site" und könnte Anfragen auslösen, kann aber wegen CORS weder Antworten noch den CSRF-Token lesen, also nichts Schreibendes ausführen.
- **Aufwand:** klein (CORS-Konfiguration, ein Endpunkt, Rücksprung-Prüfung).
- **Nachteil:** Funktioniert nur, solange Hub und Auth auf derselben Site liegen. Zöge der Hub auf eine andere Domain um, bräuchte man Variante 3. Außerdem ist die Hub-UI keine OIDC-Anwendung, das ist für einen Hub, der nur das Backend selbst anspricht, kein Nachteil.

## Variante 2: Hub als öffentlicher OIDC-Client mit PKCE

Der Hub nutzt den eigenen Provider wie jeden anderen Dienst: Authorization-Code-Flow mit PKCE, danach Aufrufe mit `Authorization: Bearer <Access-Token>`.

- **Vorteil:** Gleicher Weg wie die Dienste, funktioniert auch bei anderer Domain, kein Cookie-Thema.
- **Nachteile:** Das Access-Token (10 Minuten) und das Refresh-Token liegen im JavaScript-Speicher der Seite und sind bei einer XSS-Lücke angreifbar. Bei jedem Neuladen wäre ein Umweg über den Provider nötig, oder man müsste Tokens speichern (gegen die Vorgabe "keine Tokens in `localStorage`"). Der Provider braucht CORS für den Token-Endpunkt. Deutlich mehr Code und Fehlerquellen (Token-Erneuerung im Hintergrund, Abmelden an zwei Stellen).

## Variante 3: Backend-for-Frontend (BFF)

Caddy leitet `anfall.net/api/*` an das Backend. Das Backend führt den OIDC-Flow für den Hub serverseitig durch und setzt ein eigenes Cookie für `anfall.net`. Die Tokens erreichen den Browser nie.

- **Vorteil:** Stärkste Trennung, auch bei anderer Domain nutzbar, kein CORS.
- **Nachteile:** Zwei Sitzungen (auth und hub), das Backend wird Client seiner selbst, mehr bewegliche Teile und ein zusätzlicher Caddy-Block. Für einen Einzelentwickler und eine kleine Userbase der aufwendigste Weg ohne Nutzen, solange Hub und Auth auf einer Site liegen.

## Warum der Login auf `auth.anfall.net` liegt (Joshuas Frage)

Das ist durch OIDC vorgegeben, keine Geschmacksfrage. Die Dienste (Vaultwarden, Audiobookshelf) schicken den Browser an die Issuer-Adresse `https://auth.anfall.net` (**[E]**, fest). Dort muss der Login stattfinden und die Sitzung gelten, damit der Authorize-Endpunkt weiß, wer da ist (Single Sign-on). Ein Login auf `anfall.net` ginge nur mit einem Cookie für alle Subdomänen (`Domain=anfall.net`). Das würde jeder Dienst auf `*.anfall.net` mitlesen können, ein Fehler dort wäre ein Fehler bei allen. Google, Keycloak und andere machen es wie hier: eine eigene Adresse für den Login, die Anwendungen woanders.

## Ehrliche Einordnung der Varianten (ohne Rücksicht auf Aufwand)

- Rein nach Lehrbuch (IETF-Empfehlung für Browser-Anwendungen) ist **Variante 3 (BFF)** das Sauberste, weil die Tokens nie im Browser ankommen und sie auch über Domaingrenzen hinweg funktioniert.
- **Der Sicherheitsunterschied zu Variante 1 ist hier aber klein.** Bei Variante 1 liegt auch kein Token im Browser-JavaScript. Bei einer XSS-Lücke im Hub kann ein Angreifer in **jeder** Variante (auch BFF) die API als der angemeldete User aufrufen, solange die Seite offen ist. BFF schützt vor Token-Diebstahl, den es in Variante 1 nicht gibt.
- **Variante 2** ist ohne Aufwand betrachtet die schwächste, weil die Tokens im JavaScript-Speicher liegen.
- Der Hub könnte auch ganz auf `auth.anfall.net` liegen (gleiche Origin, kein CORS). Das widerspricht aber der Vorgabe, dass der Hub auf `anfall.net` liegt (**[E]**).

## Empfehlung

**Variante 1.** Sie ist die einfachste, hat nur eine Sitzung, hält Tokens aus dem Browser-JavaScript heraus und passt zur Vorgabe. Die Bedingung "gleiche Site" ist bei `anfall.net` und `auth.anfall.net` erfüllt. Sollte der Hub später auf eine andere Domain ziehen, lässt sich Variante 3 nachrüsten, ohne den Provider (Etappe 3) zu ändern.

Der OIDC-Provider (Etappe 3) ist von dieser Wahl nicht betroffen, nur die Hub-Schnittstelle (Etappe 4 bis 6).
