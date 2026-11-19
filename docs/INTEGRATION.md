# Dienste anbinden (Integrationsleitfaden)

Für Entwickler eigener oder umgeschriebener Dienste. Gilt für den Hub `https://anfall.net`. Die maschinenlesbare Beschreibung der Dienst-Schnittstelle steht in [`docs/api/openapi.yaml`](api/openapi.yaml), ein lauffähiges Beispiel in [`docs/examples/service_client.py`](examples/service_client.py).

Stand der Beispiele: Die `curl`-Befehle und das Python-Beispiel wurden gegen einen echten Stack ausprobiert (Token holen, Rechte abfragen, melden, Token prüfen). Für andere Sprachen gibt es hier keine Beispiele, weil ich sie nicht ausprobieren konnte. Es gelten dieselben Schritte mit einer OIDC- und einer JWT-Bibliothek der jeweiligen Sprache.

## 1. Überblick

Der Hub ist der zentrale **OpenID-Connect-Provider (OIDC)** für alle Dienste. Ein Dienst ist ein **OIDC-Client** und stellt keine eigene Benutzerverwaltung auf. Das bringt:

- **Einmal anmelden, überall drin:** Wer im Hub angemeldet ist, bekommt von jedem Dienst, für den er das Recht hat, sofort einen Code, ohne erneute Eingabe. Auch wenn er den Dienst direkt aufruft statt über den Hub.
- **Zugriff wird am Provider entschieden:** Ohne das Recht `service.<key>` gibt der Provider keinen Code aus. Der Dienst muss das nicht selbst prüfen.
- **Rechte feiner als "darf rein"** (Rollen, Zahlenwerte wie ein Kontingent) bekommt der Dienst über die Dienst-Schnittstelle (Abschnitt 6).

| Begriff | Bedeutung |
| :- | :- |
| Aussteller (Issuer) | `https://anfall.net`. Fest, ändert sich nie. Steht als `iss` in jedem Token. |
| Dienst-Schlüssel (`key`) | Kurzer Name des Dienstes, z. B. `media`. Ist zugleich die **Client-ID** und das **Präfix seiner Rechte**. |
| `sub` | Die Kennung eines Nutzers: eine UUID, die sich nie ändert. **Das** ist der Schlüssel für Nutzer im Dienst (nicht Username oder E-Mail, die sich ändern können). |
| Recht | Ein benannter Schalter oder eine Zahl, z. B. `media.upload`, `media.quota_gb`. Pflegt der Admin im Hub (Rollen, direkte Rechte). |

## 2. Was der Hub bereitstellt

| Schnittstelle | Adresse | Zweck |
| :- | :- | :- |
| Discovery | `GET /.well-known/openid-configuration` | Alle Adressen und Fähigkeiten, wie bei OIDC üblich |
| Authorize | `GET /oauth2/authorize` | Anmeldung eines Nutzers (Authorization Code mit PKCE) |
| Token | `POST /oauth2/token` | Code gegen Tokens tauschen, Refresh, und das Token des Dienstes selbst (client_credentials) |
| JWKS | `GET /oauth2/jwks` | Öffentliche Schlüssel zum Prüfen der Signatur (RS256) |
| UserInfo | `GET /userinfo` | Claims des Nutzers mit seinem Access-Token |
| Rechte eines Nutzers | `GET /api/service/users/{sub}/permissions` | Siehe Abschnitt 6.2 |
| Nutzer abfragen (Name, Adresse) | `GET /api/service/users` | Siehe Abschnitt 6.3 |
| Abmelde-Meldung an den Dienst | `POST <Logout-Adresse des Dienstes>` | Der Hub ruft den Dienst, siehe Abschnitt 5.6 |
| Eigene Daten, Meldungen | `GET`, `PUT /api/service/self` | Siehe Abschnitt 7 |

Die Discovery nennt noch weitere Standardendpunkte und Verfahren (Introspection, Revocation, weitere Client-Authentifizierungen). **Zugesagt und getestet** sind nur die in dieser Tabelle, und als Client-Authentifizierung nur `client_secret_basic` und `client_secret_post`.

## 3. Was ein Dienst bereitstellen muss

| Schnittstelle des Dienstes | Pflicht | Beschreibung |
| :- | :- | :- |
| **Rücksprung-Adresse** (Callback) | ja | Eine oder mehrere Adressen, an die der Hub nach der Anmeldung zurückleitet, z. B. `https://media.anfall.net/auth/callback`. Muss **genau** so im Hub eingetragen sein (kein Platzhalter). Erlaubt: `https`, `http` nur für `localhost`, oder das Schema einer App (`meineapp://callback`). |
| **Health-Adresse** | empfohlen | `GET` ohne Anmeldung, Antwort **2xx oder 3xx innerhalb von 5 Sekunden**, solange der Dienst läuft. Der Hub prüft sie alle 60 Sekunden und zeigt den Status. Keine vertraulichen Angaben in der Antwort. Alternativ meldet der Dienst seinen Status selbst (Abschnitt 7). |
| **Token prüfen** | ja | Erhaltene Tokens sind zu prüfen (Abschnitt 5.3), nie blind zu glauben. |
| **Sitzung des Dienstes** | ja | Nach der Anmeldung führt der Dienst eine eigene Sitzung. Empfohlen: kurze Lebensdauer und regelmäßig per Refresh-Token erneuern (Abschnitt 5.5), dann wirken Entzug von Rechten und Bann spätestens beim nächsten Refresh. |
| **Logout-Adresse** (Back-Channel-Logout) | empfohlen | `POST`-Adresse, die der Hub aufruft, wenn sich jemand im Hub abmeldet oder gesperrt wird (Abschnitt 5.6). Der Dienst beendet dann seine Sitzung. Ohne diese Adresse endet die Sitzung des Dienstes spätestens beim nächsten Refresh. |
| **Einbetten** | optional | Soll der Hub den Dienst in einem Rahmen zeigen (Öffnungsmodus `embedded`), muss der Dienst das erlauben (Abschnitt 11). |

## 4. Dienst im Hub anlegen

Im Dashboard (`/admin`, Reiter **Services**, Recht `admin.services.manage`, 2FA nötig):

1. **Dienst anlegen:** Schlüssel (2 bis 32 Zeichen, `a-z 0-9 - _`), Anzeigename, Rücksprung-Adressen, optional Adresse zum Öffnen. Der Schlüssel lässt sich nicht mehr ändern.
2. Der Hub zeigt **einmal** die **Client-ID** (= Schlüssel) und das **Client-Geheimnis**. Es steht danach nirgends mehr im Klartext (nur ein Hash in der Datenbank). Im Dienst als Geheimnis speichern (Umgebungsvariable oder Secret-Speicher, nie im Quelltext). Ein neues Geheimnis erzeugt man im Dashboard, das alte gilt dann sofort nicht mehr.
3. Das Recht **`service.<key>`** (Zugang zum Dienst) entsteht automatisch. Es wird Rollen oder Nutzern zugewiesen.
4. Beschreibung, Adresse, Health-Adresse, Akzentfarbe, **Öffnungsmodus** (neuer Tab oder im Hub eingebettet) und **Logout-Adresse** des Dienstes einstellen. Das Dashboard zeigt unter "Anbindung" Aussteller und Adressen für die Konfiguration des Dienstes.
5. Eigene Rechte des Dienstes (Reiter **Rights**) mit dem Präfix `<key>.` anlegen, z. B. `media.upload` (boolesch) oder `media.quota_gb` (Zahl, Richtung "höher = mehr", optional "unbegrenzt").

Die Befehlszeile geht weiterhin (`auth-admin register-service`), das Dashboard ist der normale Weg.

## 5. Nutzer anmelden (OIDC, Authorization Code mit PKCE)

### 5.1 Ablauf

```
Nutzer ruft den Dienst auf
 └─ Dienst leitet zu  GET https://anfall.net/oauth2/authorize?...        (Schritt 1)
     ├─ ohne Sitzung: Login-Seite des Hubs, danach zurück zum Authorize
     ├─ ohne Recht service.<key>: Seite "Kein Zugang", kein Code
     └─ mit Recht: Weiterleitung an die Rücksprung-Adresse  ?code=...&state=...
 └─ Dienst tauscht den Code  POST https://anfall.net/oauth2/token        (Schritt 2)
 └─ Dienst prüft das ID-Token und legt seine Sitzung an                       (Schritt 3)
```

### 5.2 Schritt 1: Authorize-Aufruf

Parameter (alle sind Pflicht außer `nonce`):

| Parameter | Wert |
| :- | :- |
| `response_type` | `code` |
| `client_id` | der Schlüssel des Dienstes, z. B. `media` |
| `redirect_uri` | eine der eingetragenen Rücksprung-Adressen, genau so |
| `scope` | `openid profile email`, mit `offline_access` für ein Refresh-Token |
| `state` | zufälliger Wert, den der Dienst beim Rücksprung wiedererkennt (Schutz gegen CSRF) |
| `code_challenge` | `BASE64URL(SHA256(code_verifier))` |
| `code_challenge_method` | `S256` (PKCE ist **Pflicht**, ohne gibt es keinen Code) |
| `nonce` | optional, wird im ID-Token zurückgegeben |

```
https://anfall.net/oauth2/authorize?response_type=code&client_id=media
  &redirect_uri=https%3A%2F%2Fmedia.anfall.net%2Fauth%2Fcallback
  &scope=openid%20profile%20email%20offline_access&state=Zf8x...
  &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256
```

`code_verifier` ist ein zufälliger Text aus 43 bis 128 Zeichen (`A-Z a-z 0-9 - . _ ~`), den der Dienst für den Tausch aufhebt. Der Code gilt **2 Minuten** und genau einmal.

### 5.3 Schritt 2: Code tauschen

```bash
curl -s https://anfall.net/oauth2/token \
  -u "media:$CLIENT_SECRET" \
  -d grant_type=authorization_code -d code="$CODE" \
  -d redirect_uri=https://media.anfall.net/auth/callback -d code_verifier="$VERIFIER"
```

Antwort:

```json
{ "access_token": "eyJ...", "refresh_token": "…", "id_token": "eyJ...", "token_type": "Bearer", "expires_in": 599, "scope": "openid profile email offline_access" }
```

Das `refresh_token` gibt es **nur mit dem Scope `offline_access`**.

### 5.4 Schritt 3: Tokens prüfen

Alle Tokens sind JWTs (RS256). **Immer prüfen**:

1. Signatur mit dem passenden Schlüssel aus `GET /oauth2/jwks` (über `kid` im Kopf des Tokens, Schlüssel zwischenspeichern, bei unbekanntem `kid` neu laden: Der Hub rotiert Schlüssel, alte bleiben 2 Tage im JWKS).
2. `iss` ist genau `https://anfall.net`.
3. `aud` enthält die eigene Client-ID.
4. `exp` liegt in der Zukunft (kleine Toleranz von einigen Sekunden für Uhrunterschiede ist in Ordnung).
5. Beim ID-Token: `nonce` stimmt, falls gesendet.

Das Python-Beispiel (`verify_user_token`) macht genau das.

**Claims des ID-Tokens** (mit den Scopes `profile` und `email`):

| Claim | Inhalt |
| :- | :- |
| `sub` | Kennung des Nutzers (UUID), stabil |
| `iss`, `aud`, `iat`, `exp`, `auth_time` | Standard |
| `sid` | Kennung der Hub-Sitzung, aus der diese Anmeldung stammt. Merke sie dir zur Sitzung des Dienstes, sie steht auch im Logout-Token (Abschnitt 5.6). Kein Geheimnis, aber nicht erratbar. |
| `preferred_username` | Username (nur mit Scope `profile`) |
| `locale` | Sprache des Kontos, `en` oder `de` (nur mit `profile`) |
| `email` | E-Mail-Adresse (nur mit Scope `email`) |
| `email_verified` | `true`, wenn die Adresse bestätigt ist (nur mit `email`) |

Der Username kann sich im Prinzip nicht ändern (er bleibt für immer belegt), die E-Mail schon. **Verknüpfe Nutzer im Dienst über `sub`.** Dieselben Claims liefert `GET /userinfo` mit dem Access-Token (`Authorization: Bearer ...`).

### 5.5 Refresh und Sitzungsdauer

| Token | Gültigkeit |
| :- | :- |
| Access-Token | 10 Minuten |
| ID-Token | 30 Minuten (nur beim Login zu prüfen) |
| Refresh-Token | 30 Tage, **einmal verwendbar** (Rotation: jede Antwort enthält ein neues, das alte ist danach ungültig) |
| Sitzung des Nutzers im Hub | 12 Stunden, mit "Eingeloggt bleiben" 30 Tage |

```bash
curl -s https://anfall.net/oauth2/token -u "media:$CLIENT_SECRET" -d grant_type=refresh_token -d refresh_token="$REFRESH"
```

**Bei jedem Refresh prüft der Hub den Zugang neu.** Ist der Nutzer gebannt, hat er das Recht `service.<key>` nicht mehr oder ist seine Hub-Sitzung (ohne "Eingeloggt bleiben") länger als 12 Stunden her, antwortet der Token-Endpunkt mit `400 {"error":"invalid_grant"}` und das Refresh-Token ist endgültig ungültig. Der Dienst beendet dann die Sitzung des Nutzers und schickt ihn zurück zur Anmeldung. **So wirken Entzug und Bann: nach spätestens einem Access-Token-Leben (10 Minuten), wenn der Dienst regelmäßig refresht.** Bereits ausgestellte Access-Tokens bleiben bis zu ihrem Ablauf gültig.

**Empfehlung für umgeschriebene Dienste:** Sitzung des Dienstes kurz halten (z. B. so lange wie das Access-Token) und im Hintergrund refreshen. Dann bekommt der Dienst Sperren und Rechteentzug ohne Sonderlösung mit. Merke dir das neue Refresh-Token **atomar** (zwei gleichzeitige Refreshs mit demselben Token schlagen fehl).

### 5.6 Gemeinsames Abmelden (Back-Channel-Logout)

**Wer sich im Hub abmeldet, ist auch in allen Diensten abgemeldet, die mit dieser Hub-Sitzung angemeldet wurden** (wie bei Google). Gemeint ist die Sitzung des Browsers oder Geräts: Wer auf dem Laptop abmeldet, bleibt mit dem Handy angemeldet. **Bann, Passwort-Reset und 2FA-Reset beenden dagegen alle Sitzungen des Nutzers** in allen Diensten.

Dafür trägt der Admin im Dashboard die **Logout-Adresse** des Dienstes ein. Der Hub ruft sie auf:

```
POST <Logout-Adresse>
Content-Type: application/x-www-form-urlencoded

logout_token=<JWT>
```

**Antwort des Dienstes:** `200` oder `204`, wenn er die Sitzung beendet hat (oder es keine gab), sonst `400`. Der Hub ruft einmal auf, mit 5 Sekunden Zeitlimit, ohne Weiterleitungen und ohne Wiederholung. Das Ergebnis steht im Protokoll (`service.backchannel_logout`). Schlägt der Aufruf fehl, bleibt es bei der zweiten Absicherung: Der Hub löscht beim Abmelden die Refresh-Tokens des Dienstes, die Sitzung endet dann spätestens beim nächsten Refresh (Abschnitt 5.5).

**Das Logout-Token** ist ein JWT (RS256, Signatur über das JWKS des Hubs, Kopf `typ` = `logout+jwt`) mit diesen Claims:

| Claim | Inhalt |
| :- | :- |
| `iss` | `https://anfall.net` |
| `aud` | Client-ID des Dienstes |
| `sub` | Nutzer (UUID) |
| `sid` | Hub-Sitzung, die endet. **Fehlt**, wenn alle Sitzungen des Nutzers enden (Bann, Reset). |
| `iat`, `exp` | Ausstellung und Ablauf (120 Sekunden später) |
| `jti` | Eindeutige Kennung des Tokens |
| `events` | `{"http://schemas.openid.net/event/backchannel-logout": {}}` |

Es enthält **kein `nonce`**. Der Dienst **prüft**: Signatur, `iss`, `aud`, `exp`, `typ`, dass `events` den Eintrag oben enthält, dass **kein `nonce`** da ist und dass `sub` oder `sid` da ist. Ein `jti`, das er schon gesehen hat, lehnt er ab (kurz merken, mindestens 2 Minuten).

**Dann beendet der Dienst Sitzungen:** Mit `sid` nur die, die er mit genau dieser `sid` aus dem ID-Token angelegt hat. Ohne `sid` alle Sitzungen dieses `sub`. Er löscht auch seine gespeicherten Refresh-Tokens dieser Sitzungen.

Die Discovery meldet `backchannel_logout_supported` und `backchannel_logout_session_supported` (beide `true`).

**Nicht Teil davon:** Meldet sich ein Nutzer im Dienst selbst ab, endet nur die Sitzung des Dienstes. Ein "überall abmelden" vom Dienst aus (RP-initiated Logout) ist noch nicht festgelegt.

**Stand der Prüfung:** Die Spezifikation OpenID Connect Back-Channel Logout 1.0 war in der Entwicklungsumgebung nicht abrufbar. Nachrichtenformat, Claims und `typ` wurden gegen die Referenzimplementierung `node-oidc-provider` 9.15.0 abgeglichen, nicht gegen den Spezifikationstext. Vor dem produktiven Einsatz gegen die Spezifikation lesen (nicht verifiziert).

## 6. Rechte eines Nutzers abfragen

Ins Token kommen **keine Rechte** (nur die Identität). Das Token bliebe sonst bei vielen Rechten sehr lang, und ein Entzug würde erst mit dem Ablauf wirken. Der Dienst fragt die Rechte stattdessen bei Bedarf ab und bekommt immer den aktuellen Stand.

### 6.1 Token des Dienstes holen

```bash
curl -s https://anfall.net/oauth2/token -u "media:$CLIENT_SECRET" -d grant_type=client_credentials -d scope=service
# {"access_token":"eyJ...","token_type":"Bearer","expires_in":599,"scope":"service"}
```

Es gilt 10 Minuten und hat **kein Refresh-Token**: Neu holen, wenn es abläuft (zwischenspeichern, ca. 30 Sekunden vor Ablauf erneuern). Es trägt `sub` = Client-ID und das Merkmal `token_use=service`. **Tokens von Nutzern werden an der Dienst-Schnittstelle immer abgelehnt (403)**, auch wenn ein Nutzer den Scope `service` anfordert.

### 6.2 Abfrage

```bash
curl -s -H "Authorization: Bearer $SERVICE_TOKEN" https://anfall.net/api/service/users/$SUB/permissions
```

```json
{
  "sub": "3f0c1d2e-7a55-4a3e-9d0b-0f6c1b7f1a11",
  "access": true,
  "permissions": [
    { "key": "media.upload",      "type": "boolean" },
    { "key": "media.quota_gb",    "type": "number", "value": 50 },
    { "key": "media.share_links", "type": "number", "unlimited": true }
  ]
}
```

Regeln:

- **Namensraum:** Es kommen nur Rechte mit dem Präfix `<dienst>.` zurück. Ein Dienst sieht nie Rechte eines anderen. Der optionale Parameter `prefix` engt weiter ein (`?prefix=media.quota`), ein Präfix außerhalb des Namensraums gibt `400 prefix_not_allowed`.
- **`access`:** `true`, wenn der Nutzer das Recht `service.<key>` hat und nicht gesperrt ist. Sonst `false` und eine leere Liste.
- **Boolesche Rechte** stehen in der Liste, wenn sie vergeben sind. Was fehlt, hat der Nutzer nicht.
- **Zahlenrechte** haben `value` (eine ganze Zahl, 0 oder größer) **oder** `unlimited: true`. Der Hub hat den Wert schon aus allen Rollen und direkten Rechten berechnet (bei "höher = mehr" der größte, bei "niedriger = mehr" der kleinste Wert). **Eine 0 ist ein gültiger Wert**, "nicht gesetzt" heißt: das Recht steht nicht in der Liste.
- **Immer aktuell:** Ein Entzug gilt sofort. Zwischenspeichern ist in Ordnung (empfohlen höchstens 60 Sekunden), wer Entzug schneller braucht, fragt öfter.
- **Fehler:** `400 sub_invalid` (keine UUID), `404 user_unknown`, `401` (kein oder ungültiges Token), `403` (kein Dienst-Token), `429` (über 600 Anfragen pro Minute und Dienst).

**Konvention für Rechte:** Rechte eines Dienstes heißen `<key>.<name>` in Kleinbuchstaben mit Ziffern und Unterstrichen (`media.upload`, `media.quota_gb`). Zahlenrechte nennen die Einheit im Namen. Der Hub legt nur diese Form an und reserviert `admin.` und `service.`.

### 6.3 Nutzer abfragen (Name und E-Mail)

**Der Dienst speichert von einem Nutzer nur die UUID (`sub`) und eigene Daten dazu.** Name, E-Mail und Sprache verwaltet allein der Hub. Der Dienst fragt sie bei Bedarf ab:

```bash
# alle Nutzer mit Zugang zum Dienst (z. B. für eine Auswahlliste)
curl -s -H "Authorization: Bearer $SERVICE_TOKEN" https://anfall.net/api/service/users
# bestimmte Nutzer (höchstens 100), auch solche ohne Zugang oder gesperrte
curl -s -H "Authorization: Bearer $SERVICE_TOKEN" "https://anfall.net/api/service/users?sub=$SUB1&sub=$SUB2"
```

```json
{
  "users": [
    { "sub": "3f0c1d2e-7a55-4a3e-9d0b-0f6c1b7f1a11", "username": "anna", "email": "anna@example.com",
      "emailVerified": true, "locale": "de", "banned": false, "access": true }
  ]
}
```

- **Ohne `sub`:** alle Nutzer, die Zugang zum Dienst haben (nicht gesperrt, Recht `service.<key>` vorhanden), sortiert nach Username.
- **Mit `sub`** (mehrfach, höchstens 100): genau diese Nutzer, in der Reihenfolge der Abfrage, **auch wenn sie keinen Zugang mehr haben** (`access: false`). So lassen sich Namen in alten Daten anzeigen. Unbekannte UUIDs fehlen in der Antwort. Eine UUID kennt der Dienst nur, weil sie sich bei ihm angemeldet hat, ein Durchsuchen aller Konten des Hubs ist nicht möglich.
- **`username`** ist die Anzeige in Listen. Einen eigenen Anzeigenamen gibt es im Hub noch nicht (siehe `docs/OPEN-QUESTIONS.md`).
- **E-Mail nur verwenden, wenn `emailVerified` wahr ist.** Mails an Nutzer schickt der Dienst selbst, die Adresse holt er sich bei Bedarf hier ab.
- **Nicht dauerhaft speichern.** Kurz zwischenspeichern (empfohlen höchstens ein paar Minuten), sonst veralten Name und Adresse, und bei einer Löschung des Kontos im Hub blieben Daten im Dienst zurück.
- **Fehler:** `400 sub_invalid` (keine UUID), `400 too_many_subs` (mehr als 100), `401`, `403` (kein Dienst-Token), `429`.

## 7. Eigene Daten lesen, Farbe und Status melden

`GET /api/service/self` liefert, wie der Hub den Dienst kennt (Name, Farbe, Status). `PUT /api/service/self` meldet:

```bash
curl -s -X PUT -H "Authorization: Bearer $SERVICE_TOKEN" -H "Content-Type: application/json" \
  -d '{"accentColor":"#22aa55","status":{"state":"up","ttlSeconds":300}}' https://anfall.net/api/service/self
```

- **`accentColor`** (`#rrggbb`): Eine gemeldete Farbe **überschreibt** die im Hub eingestellte, **außer** ein Admin hat im Hub festgehalten, dass seine Farbe bleibt. `""` löscht die gemeldete Farbe. Der Hub färbt damit die Knöpfe des Dienstes.
- **`status`** (`up` oder `down`, `ttlSeconds` 30 bis 900, Standard 180): gilt für diese Zeit und hat dann Vorrang vor der Abfrage der Health-Adresse. Wer meldet, schickt alle paar Minuten ein neues `up`. Läuft die Zeit ab, ohne dass etwas Neues kommt, gilt wieder die Abfrage der Health-Adresse.
- Fehlende Felder bleiben unverändert. Ist etwas ungültig, wird **nichts** übernommen (`400`, Fehlercodes `service_color_invalid`, `status_state_invalid`, `status_ttl_invalid`).

## 8. Fehlerformat und Grenzen

- Fehler der Hub-API haben die Form `{"error": "<code>"}` mit einem kurzen, maschinenlesbaren Code, plus den passenden HTTP-Status. Standardfehler der OAuth-Endpunkte (`/oauth2/*`) folgen RFC 6749 (`invalid_grant`, `invalid_client`, `invalid_scope`).
- Die Dienst-Schnittstelle ist auf **600 Anfragen pro Minute und Dienst** begrenzt (`429`).
- Antworten sind nicht zwischenspeicherbar (`Cache-Control: no-store`).
- Alle Zeiten in Tokens sind UTC (Unix-Sekunden).
- Es gibt kein CORS: Die Schnittstellen sind für Server-zu-Server-Aufrufe gedacht, **nicht für Browser-JavaScript**. Das Client-Geheimnis darf nie in einem Browser oder einer App landen.

## 9. Sicherheitshinweise

1. **Client-Geheimnis geheim halten** und nicht ins Repository legen. Bei Verdacht im Dashboard ein neues erzeugen.
2. **`state` und PKCE immer verwenden** und den `state` beim Rücksprung prüfen.
3. **Tokens vollständig prüfen** (Abschnitt 5.4). Nur Tokens verwenden, die vom Hub stammen.
4. **Nutzer über `sub` verknüpfen**, nicht über Username oder E-Mail.
5. **Rechte nicht dauerhaft im Dienst speichern**, sondern abfragen (kurz zwischenspeichern).
6. **Refresh-Tokens** wie Passwörter behandeln (serverseitig, verschlüsselt, nicht im Browser).
7. Der Hub **ändert den Aussteller nie** und rotiert die Signierschlüssel. Immer über das JWKS prüfen, keine Schlüssel fest einbauen.

## 10. Kurzbeispiel (Python)

```python
import service_client as hub          # docs/examples/service_client.py

info = hub.user_permissions(sub)      # {'access': True, 'permissions': [...]}
if not info["access"]:
    ...                               # Sitzung beenden
quota = next((p for p in info["permissions"] if p["key"] == "media.quota_gb"), None)
limit = float("inf") if quota and quota.get("unlimited") else (quota["value"] if quota else 0)
hub.report(state="up")                # z. B. alle 2 Minuten im Hintergrund
claims = hub.verify_user_token(id_token)
```

## 11. Einbetten in den Hub

Jeder Dienst ist auf **zwei Arten** nutzbar:

1. **Als eigene Webseite** unter einer eigenen Subdomain (z. B. `https://abos.anfall.net`). Das geht immer. Wer im Hub angemeldet ist, ist ohne Eingabe auch hier angemeldet (Abschnitt 5.1).
2. **Im Hub eingebettet.** Der Admin stellt den Öffnungsmodus des Dienstes im Dashboard auf `embedded`. Der Hub zeigt dann unter `https://anfall.net/app/<key>` sein Seitenmenü und lädt die **Adresse zum Öffnen** des Dienstes in einem Rahmen (`iframe`) daneben. Im Modus `new_tab` öffnet der Dienst in einem neuen Tab.

**Was der Dienst dafür tut:**

1. **Unter einer Subdomain von `anfall.net` laufen.** Hub und Dienst sind dann dieselbe "Site". Nur dann werden Cookies des Dienstes (auch mit `SameSite=Strict` oder `Lax`) im Rahmen mitgeschickt. Liefe der Dienst unter einer fremden Domain, wären sie Drittanbieter-Cookies, die Browser oft blockieren.
2. **Das Einbetten durch den Hub erlauben:** Antwort-Header `Content-Security-Policy: frame-ancestors https://anfall.net` und **kein** `X-Frame-Options: DENY` oder `SAMEORIGIN`. Alle anderen Seiten bleiben nicht einbettbar.
3. **Optional eine eigene Ansicht für den Rahmen liefern.** Der Browser sendet bei Anfragen, die ein Rahmen auslöst, den Header `Sec-Fetch-Dest: iframe` (er beginnt mit `Sec-` und lässt sich von JavaScript nicht setzen). Daran erkennt der Dienst den Rahmen und kann eine reduzierte Ansicht ausgeben: **ohne eigene Kopfzeile, Seitenmenü und Abmelden-Knopf**, denn die hat der Hub bereits. Das gilt für jede Seite im Rahmen (der Header kommt bei jeder Navigation im Rahmen wieder). Ohne eigene Ansicht wird die normale Seite gezeigt. Der Header gilt als vorhanden in Chrome, Firefox und Safari, die Mindestversionen sind nicht verifiziert.
4. **Sich selbst nicht aus dem Rahmen befreien** (keine Frame-Buster-Skripte, kein `target="_top"`).

**Was der Hub tut:**

- Der Rahmen hat `sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox allow-downloads"` und `referrerpolicy="no-referrer"`. Ohne `allow-top-navigation` kann der Dienst den Hub nicht wegnavigieren. `allow-same-origin` gilt für den Ursprung des Dienstes (eine andere Adresse als der Hub), nicht für den des Hubs.
- Der Hub lädt den Rahmen nur für Dienste, die der Nutzer nutzen darf und die auf `embedded` stehen, und nur bei hinterlegter Adresse. Er erlaubt auf dieser einen Seite `frame-src https:`.
- Die Anmeldung im Rahmen ist die normale (Abschnitt 5.1). Ist der Nutzer im Hub angemeldet, läuft sie ohne Eingabe durch. Die Weiterleitungen enden im Dienst, es erscheint keine Seite des Hubs im Rahmen.
- Meldet sich der Nutzer im Hub ab, wird die Sitzung des Dienstes beendet (Abschnitt 5.6), der Hub wechselt zur Anmeldeseite.

**Grenzen und offene Punkte:**

- Zeigt der Hub eine eigene Seite im Rahmen (z. B. "Kein Zugang" bei fehlendem Recht), bleibt der Rahmen leer, weil der Hub nicht einbettbar ist. Das kommt nur vor, wenn der Nutzer im Hub gerade das Recht verloren hat. Eine eigene Meldung dafür ist noch nicht festgelegt.
- Nachrichten zwischen Hub und Dienst (Titel, Höhe, Navigation per `postMessage`) sind **nicht festgelegt**.
- Die Aussagen zu Cookies, `Sec-Fetch-Dest` und den Einbett-Headern stützen sich auf die MDN-Dokumentation (Stand der Prüfung 19.11.2026). Das Verhalten im echten Browser mit einem echten Dienst ist noch nicht getestet.

## 12. Offen und noch nicht festgelegt

Die Meldung weiterer Eigenschaften durch den Dienst (außer Farbe und Status), Nachrichten zwischen Hub und eingebettetem Dienst, ein Abmelden vom Dienst aus für alle Dienste, ein eigener Anzeigename im Hub. Siehe `docs/OPEN-QUESTIONS.md`.
