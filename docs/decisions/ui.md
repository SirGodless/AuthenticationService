# UI (UserHub)

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Webserver: nginx-unprivileged** (Abschnitt 2 überlässt die Wahl dem Entwickler). Begründung: Das Image läuft ab Werk ohne Root-Rechte, lauscht auf Port 8080 per HTTP, braucht für diese Aufgabe nur eine kleine Konfiguration (Header, ein Pfad-Muster für `/setup/<token>`) und ist weit verbreitet. Ein schlanker Caddy wäre auch möglich gewesen, aber Joshua will sich mit Caddy-Konfiguration nicht weiter befassen. Image: `nginxinc/nginx-unprivileged:1.30-alpine` (nginx 1.30.6). **[U]** Ein Tag mit Patch-Version (`1.30.6-alpine`) konnte ich nicht prüfen, Docker Hub hat in der Sitzung das Abfragelimit gemeldet.
- **[A] QR-Code:** `qrcode-generator` 2.0.4 (MIT) liegt selbst gehostet in `public/assets/vendor`, kein CDN. Das Geheimnis verlässt die Seite nicht.
- **[A] Sicherheits-Header** der UI: strenge CSP (`default-src 'none'`, Skripte und Styles nur von der eigenen Adresse, `frame-ancestors 'none'`), `no-referrer`, `nosniff`, keine Zwischenspeicherung der Setup-Seite.
- **[A] Sprache:** Standard Englisch, Umschalter oben rechts, Wahl nur im Browser gemerkt. Bei Abschluss des Setups wird die gewählte Sprache als Account-Sprache gespeichert.
- Styling ist bewusst schlicht, CSS-Variablen, Dark Mode zuerst. Der Stil kommt in Etappe 6.

- **Etappe 3:** Neue Seite `/access-denied` ("Kein Zugang", Dienstname aus dem Parameter `service`, nur als Text eingesetzt). Die Login-Seite folgt dem Parameter `continue` nur für Adressen, die mit `/oauth2/authorize?` beginnen (`js/shared/redirect.js`), damit ein Dienst den Nutzer nach dem Login dorthin zurückbringt, ohne dass ein offener Redirect entsteht.
- **Aufbau von `public/` (13.11.2026):** Siehe `docs/decisions/code-structure.md`.
- **[A] Passwortfelder (NIST SP 800-63B-4):** Alle Passwortfelder haben "Passwort anzeigen", Einfügen ist nicht gesperrt, Passwortmanager funktionieren (`autocomplete`-Angaben), unter neuen Passwörtern steht eine Hilfestellung. Das Backend liefert bei Ablehnung einen Grund, die UI zeigt ihn an.
