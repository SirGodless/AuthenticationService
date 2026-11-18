# Dienste anlegen und anbinden (Zusatz nach Etappe 7)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

Hintergrund (Joshua, 18.11.2026): Über den Hub laufen vor allem **eigene oder umgeschriebene Dienste**, damit sie sich ordentlich steuern lassen. Es gibt noch keinen Standard, nach dem diese gebaut werden. Deshalb legt der Hub ihn fest und beschreibt ihn: **`docs/INTEGRATION.md`** (Leitfaden mit Beispielen), **`docs/api/openapi.yaml`** (OpenAPI 3.1, mit `openapi-spec-validator` geprüft) und **`docs/examples/service_client.py`** (lauffähig, gegen einen echten Stack ausprobiert).

## Entscheidungen

- **[E] Dienste im Dashboard anlegen.** Anlegen (Schlüssel, Name, Rücksprung-Adressen, optional Beschreibung, Adresse, Statusadresse, Farbe), Rücksprung-Adressen ändern, neues Client-Geheimnis erzeugen. Das Geheimnis steht nur in der Antwort darauf (einmal sichtbar), danach nirgends im Klartext (nur ein Argon2-Hash, nie im Protokoll). Ein neues Geheimnis macht das alte sofort ungültig, schon ausgestellte Tokens bleiben gültig. Recht `admin.services.manage` und 2FA.
- **[E] Rechte nicht im Token.** Ein Token mit vielen Rechten würde lang (Header- und Cookie-Grenzen von etwa 8 und 4 KB) und ein Entzug erst nach dem Ablauf wirken. Stattdessen fragt der Dienst die Rechte ab (`GET /api/service/users/{sub}/permissions`) und bekommt immer den aktuellen Stand. Das Token bleibt klein (Test: unter 1200 Zeichen) und enthält nur die Identität.
- **[E] Namensraum.** Rechte eines Dienstes tragen seinen Schlüssel als Präfix (`media.upload`). Ein Dienst sieht nur Rechte aus seinem Namensraum, der Parameter `prefix` kann nur weiter einengen. Das Dashboard zeigt in der Rechteliste, zu welchem Dienst ein Recht gehört.
- **[E] Meldeweg (Entscheidung von mir, Joshua: "Entscheide du").** `PUT /api/service/self`: Farbe und Status. Die gemeldete Farbe überschreibt die im Hub eingestellte, außer sie ist dort festgehalten. Der gemeldete Status gilt 30 bis 900 Sekunden (Standard 180) und hat in dieser Zeit Vorrang vor der Abfrage der Health-Adresse. **[A]** Der Dienst darf Beschreibung, Name und Adresse nicht melden (die bestimmt der Admin), sonst könnte ein kompromittierter Dienst die Hub-Oberfläche beliebig beschriften.
- **[E] Abmelden im Hub beendet die Dienste nicht.** Joshua will das beim Umschreiben der Dienste sauber lösen. Der Hub liefert dafür den Weg über kurze Sitzungen und Refresh (jeder Refresh prüft Bann und Recht neu). Back-Channel-Logout ist reserviert, aber nicht gebaut.
- **[E]** Vaultwarden wird separat verwaltet und kommt eventuell gar nicht auf den Hub.

## Technik (**[A]**)

- **Token des Dienstes:** OAuth-2-Standard `client_credentials` mit dem Scope `service` am normalen Token-Endpunkt. Jeder angelegte Dienst darf das. 10 Minuten, kein Refresh-Token. Das Token trägt `sub` = Client-ID und das Merkmal `token_use=service`.
- **Trennung von Nutzer-Tokens:** Die Dienst-Schnittstelle akzeptiert nur Tokens mit `token_use=service`. Ein Nutzer kann sich zwar den Scope `service` anfordern (er ist im Client eingetragen), sein Token trägt das Merkmal aber nie und wird mit 403 abgelehnt (getestet mit beiden Scope-Varianten).
- **Eigene Filterkette** für `/api/service/**`: Bearer-Token (JWT, gegen die eigenen Schlüssel geprüft), keine Sitzung, kein CSRF-Schutz (es gibt keine Cookies). Fehlendes oder ungültiges Token gibt 401.
- **Rücksprung-Adressen:** `https`, `http` nur für `localhost`, `127.0.0.1`, `[::1]`, oder das Schema einer App (z. B. `audiobookshelf://oauth`). Nie `javascript:`, `data:`, `file:`, `ftp:` und so weiter, keine Platzhalter, kein Fragment, höchstens 10 und 300 Zeichen.
- **Begrenzung:** 600 Anfragen pro Minute und Dienst (`429`).
- **Rechte einer Anfrage:** Hat der Nutzer das Recht `service.<key>` nicht oder ist er gesperrt, ist `access` false und die Liste leer.
- **Migration:** V21 hat `reported_accent_color` und `accent_locked` schon vorbereitet. Bestehende Clients (vor dieser Änderung angelegt) bekämen den Grant `client_credentials` nicht automatisch. Da noch nichts deployt ist, entfällt die Nacharbeit. Wer einen Dienst vor dieser Änderung angelegt hat, legt ihn neu an.

## Getestet

- `ServiceDashboardTest` (9), `ServiceApiTest` (22), Browser-Test (Anlegen im Dashboard, Geheimnis einmal sichtbar, Rücksprung-Adressen, Token holen, Rechte abfragen, Melden, Anzeige im Hub) und das Python-Beispiel gegen den echten Stack.
- **[U]** Das Zusammenspiel mit einem echten fremden OIDC-Client (Bibliothek) wurde nicht geprüft, nur mit dem Testclient und `curl`.
