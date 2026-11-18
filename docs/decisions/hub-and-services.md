# Hub, Dienste im Hub und Gestaltung (Etappe 6)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung. **[U]** nicht verifiziert.

## Gestaltung (**[E]**, Joshua, 18.11.2026)

Gewählt wurde der Vorschlag "Raster": klare Linien und Flächen, kleine Rundungen (5 px, Karten 8 px), kleine Mono-Beschriftungen in Großbuchstaben, normal geschriebene Überschriften, **neutrale Grautöne ohne Blaustich**, Indigo (`#8c99ff` dunkel, `#3a49d6` hell) als einzige allgemeine Akzentfarbe. Dunkel ist der Standard, **Hell und Dunkel sind umschaltbar** (Umschalter oben rechts, die Wahl merkt der Browser, ohne Wahl gilt die Einstellung des Systems). Alle Farben liegen als Variablen in `tokens.css`, ein neuer Modus oder eine neue Farbe ist dort ein Satz Variablen. Der erste Entwurf mit Rot wie in Joshuas Beispiel wurde wegen der Vorgabe "kein Schwarz mit Rot" nicht übernommen.

Aufbau nach dem Login: **Seitenmenü links** (Dienste, Konto-Links, Name, Abmelden), Inhalt rechts. Seiten vor dem Login (Login, Passwort vergessen, Registrierung, Setup, Kein Zugang) haben dieselbe Gestaltung als zentrierte Karte.

## Akzentfarbe pro Dienst (**[E]**, Wunsch von Joshua)

Jeder Dienst hat eine eigene Akzentfarbe. Sie färbt im Hub den "Öffnen"-Knopf, das Farbquadrat vor dem Namen und die Kante beim Überfahren im Seitenmenü.

- Gespeichert in `services.accent_color` (V20), `#rrggbb` klein geschrieben oder leer (dann gilt der allgemeine Akzent). Das Backend prüft das Format (`service_color_invalid`), die Oberfläche nur zur Vorschau.
- **Einstellen** im Dashboard, Reiter "Dienste" (Recht `admin.services.manage`): Farbwähler, Hex-Feld, "Standard nutzen" und eine Vorschau des Knopfes. Dort stehen auch Anzeigename, Beschreibung, Adresse zum Öffnen und Adresse für die Statusprüfung.
- **[A]** Die Schriftfarbe auf dem Knopf wird automatisch gewählt (Schwarz oder Weiß, je nachdem, was auf der Farbe besser lesbar ist), damit jede gewählte Farbe lesbar bleibt. Ein nicht erreichbarer Dienst bekommt einen nur umrandeten Knopf mit normaler Schriftfarbe (eine beliebige Dienstfarbe wäre als Schrift auf hellem oder dunklem Grund nicht immer lesbar).
- Die Farbe wird per Skript gesetzt (CSS-Variable `--svc`), nicht als Inline-Style im HTML, damit die strenge CSP ohne Inline-Styles auskommt.
- **Später** sollen Dienste die Farbe selbst melden können. Dafür ist noch nichts gebaut. Es gibt bewusst noch keine Spalte für die Herkunft der Farbe. Wenn es soweit ist, kommt sie in einer neuen Migration dazu, und die Verwaltung im Dashboard bleibt als Überschreiben bestehen.

## Hub-Daten (`GET /api/hub`)

Eine Schnittstelle für Seitenmenü und Startseite (Paket `hub`): die **Dienste, die der User nutzen darf** (die Entscheidung fällt im Backend anhand des Rechts des Dienstes, live berechnet, die Rolle Admin gibt keine Dienste), je Dienst Name, Beschreibung, Adresse, Öffnungsmodus, Farbe und Status, dazu Angaben zum Konto (Name, Sprache, 2FA, ob Admin, Links erstellbar, aktive Links und Limit). Die Oberfläche zeigt nur an.

- Öffnen: neuer Tab (`open_mode = new_tab`, **[E]**), `rel="noopener noreferrer"`. Eingebettet gibt es noch nicht.
- **[A]** Ein als offline markierter Dienst lässt sich weiter öffnen (die Prüfung kann irren), der Knopf ist dann nur umrandet. Ohne Adresse ist der Knopf deaktiviert.

## Statusanzeige (**[E]** laut Abschnitt 8, Umsetzung **[A]**)

`service/ServiceHealthService` prüft **alle 60 Sekunden** die Health-Adresse jedes Dienstes vom Server aus und merkt das Ergebnis nur im Speicher. Aktiv heißt Antwort 2xx oder 3xx innerhalb von 5 Sekunden (Weiterleitungen werden nicht verfolgt). Ohne Health-Adresse oder vor der ersten Prüfung ist der Status "unbekannt". Nur eine Anzeige, keine Alarm-Mails (**[E]**). Die Startseite holt den Status alle 30 Sekunden neu.

- **Mailserver nur für Admins sichtbar (**[E]**):** Das Backend prüft zusätzlich, ob der Mailserver Verbindungen annimmt (TCP auf `MAIL_HOST:MAIL_PORT`). Die Antwort enthält diesen Status nur für User mit mindestens einem `admin.*`-Recht. **[A]** Das ist eine Erreichbarkeitsprüfung, kein Zustelltest.
- **[A]** Die Adressen für die Prüfung stellt nur ein Admin mit `admin.services.manage` ein, sie müssen `http` oder `https` mit Host sein. Der Server ruft diese Adressen auf, deshalb gehört das Recht dazu nur in vertrauenswürdige Hände.
- Getestet mit einem lokalen Testserver: 200 und 302 gelten als aktiv, 500 und eine nicht erreichbare Adresse als inaktiv, ohne Adresse unbekannt, und der Status folgt einem Dienst, der zurückkommt oder ausfällt.

## Profilseite (`/account`)

Sprache des Kontos (`PUT /api/auth/language`, Mails kommen danach in dieser Sprache, die Oberfläche nach dem Login folgt ihr) und Passwort ändern (bestehende Schnittstelle, andere Sitzungen enden). Zwei-Faktor und eigene Einladungslinks haben weiter ihre eigenen Seiten, das Seitenmenü und die Startseite verlinken sie.

## Dienste anlegen

Das Anlegen eines Dienstes samt OIDC-Client bleibt vorerst beim Befehl `auth-admin register-service`, weil dabei das Client-Geheimnis einmal angezeigt wird. Das Dashboard ändert nur die Anzeige-Eigenschaften.

Tests: `ServiceAdminApiTest`, `ServiceHealthTest`, `HubApiTest`, `LanguageTest`, dazu der Browser-Test (Farbe im Hub gemessen, Hell/Dunkel, Kontosprache, Seitenmenü).
