# Anmeldung und Sitzungen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Eigene Sitzungstabelle** (`login_sessions`) statt Spring Session. So lassen sich Sitzungen eines Users gezielt beenden (Reset, später Bann) und die Laufzeiten genau steuern. Das Cookie enthält 256 Bit Zufall, in der Datenbank steht nur der SHA-256.
- **[A] Cookie:** `__Host-sid` (der Browser erzwingt damit Secure, Pfad `/` und kein Domain-Attribut, also nur für `auth.anfall.net` selbst), `HttpOnly`, `Secure`, `SameSite=Lax`. Mit "Eingeloggt bleiben" 30 Tage und persistent, sonst Sitzungs-Cookie mit 12 Stunden auf dem Server. Alle Werte einstellbar (`app.session.*`). Im Browser geprüft (Playwright).
- **[A] Die Laufzeiten 12 Stunden und 30 Tage** aus Abschnitt 3 gelten hier für die Anmeldesitzung im Browser. Die Token-Laufzeiten (10 Minuten Access-Token, Refresh-Token) kommen mit Etappe 3 und hängen dort an dieser Sitzung.
- **[A] CSRF:** Cookie `XSRF-TOKEN` (für die UI lesbar, `Secure`, `SameSite=Lax`), die UI schickt ihn als Header `X-XSRF-TOKEN` zurück. Ohne Header 403. Ausgenommen ist nur `/api/setup/**` (dort schützt der geheime Token im Pfad). **Die UI muss auf derselben Adresse wie die API laufen** (`auth.anfall.net`, Caddy verteilt die Pfade). Für den Hub auf `anfall.net` ist das offen (Frage "Hub-UI an der API", vor Etappe 3).
- **[A] Zwei Schritte bei 2FA:** Nach dem Passwort entsteht eine wartende Sitzung (5 Minuten, zählt nicht als angemeldet). Erst der Code erzeugt eine **neue** Sitzung (gegen Session Fixation).
- **[A] Antworten verraten nichts:** Falsches Passwort und unbekannter Username geben dieselbe Antwort und rechnen gleich lange (Dummy-Hash). "E-Mail nicht bestätigt" und "gesperrt" werden erst nach **richtigem** Passwort gemeldet.
- **[A] Passwort ändern** (angemeldet, aktuelles Passwort nötig): beendet die anderen Sitzungen, die aktuelle bleibt. Der Reset per Mail beendet alle.
- **[A] TOTP-Code nur einmal:** Jeder 30-Sekunden-Schritt gilt einmal (bedingtes UPDATE, auch bei gleichzeitigen Anfragen). Im Login gibt man den 6-stelligen Code oder einen Wiederherstellungscode in dasselbe Feld ein.
