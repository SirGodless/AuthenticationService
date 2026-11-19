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
| Rechte eines Nutzers | `GET /api/service/users/{sub}/permissions` | Siehe Abschnitt 6 |
| Eigene Daten, Meldungen | `GET`, `PUT /api/service/self` | Siehe Abschnitt 7 |

Die Discovery nennt noch weitere Standardendpunkte und Verfahren (Introspection, Revocation, weitere Client-Authentifizierungen). **Zugesagt und getestet** sind nur die in dieser Tabelle, und als Client-Authentifizierung nur `client_secret_basic` und `client_secret_post`.

## 3. Was ein Dienst bereitstellen muss

| Schnittstelle des Dienstes | Pflicht | Beschreibung |
| :- | :- | :- |
| **Rücksprung-Adresse** (Callback) | ja | Eine oder mehrere Adressen, an die der Hub nach der Anmeldung zurückleitet, z. B. `https://media.anfall.net/auth/callback`. Muss **genau** so im Hub eingetragen sein (kein Platzhalter). Erlaubt: `https`, `http` nur für `localhost`, oder das Schema einer App (`meineapp://callback`). |
| **Health-Adresse** | empfohlen | `GET` ohne Anmeldung, Antwort **2xx oder 3xx innerhalb von 5 Sekunden**, solange der Dienst läuft. Der Hub prüft sie alle 60 Sekunden und zeigt den Status. Keine vertraulichen Angaben in der Antwort. Alternativ meldet der Dienst seinen Status selbst (Abschnitt 7). |
| **Token prüfen** | ja | Erhaltene Tokens sind zu prüfen (Abschnitt 5.3), nie blind zu glauben. |
| **Sitzung des Dienstes** | ja | Nach der Anmeldung führt der Dienst eine eigene Sitzung. Empfohlen: kurze Lebensdauer und regelmäßig per Refresh-Token erneuern (Abschnitt 5.5), dann wirken Entzug von Rechten und Bann spätestens beim nächsten Refresh. |
| Back-Channel-Logout | **reserviert** | Noch nicht festgelegt. Abmelden im Hub beendet die Sitzung des Dienstes nicht von selbst. Der Dienst soll das über kurze Sitzungen und Refresh lösen. |

## 4. Dienst im Hub anlegen

Im Dashboard (`/admin`, Reiter **Services**, Recht `admin.services.manage`, 2FA nötig):

1. **Dienst anlegen:** Schlüssel (2 bis 32 Zeichen, `a-z 0-9 - _`), Anzeigename, Rücksprung-Adressen, optional Adresse zum Öffnen. Der Schlüssel lässt sich nicht mehr ändern.
2. Der Hub zeigt **einmal** die **Client-ID** (= Schlüssel) und das **Client-Geheimnis**. Es steht danach nirgends mehr im Klartext (nur ein Hash in der Datenbank). Im Dienst als Geheimnis speichern (Umgebungsvariable oder Secret-Speicher, nie im Quelltext). Ein neues Geheimnis erzeugt man im Dashboard, das alte gilt dann sofort nicht mehr.
3. Das Recht **`service.<key>`** (Zugang zum Dienst) entsteht automatisch. Es wird Rollen oder Nutzern zugewiesen.
4. Beschreibung, Adresse, Health-Adresse und Akzentfarbe des Dienstes einstellen. Das Dashboard zeigt unter "Anbindung" Aussteller und Adressen für die Konfiguration des Dienstes.
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

## 11. Offen und noch nicht festgelegt

Back-Channel-Logout (der Hub benachrichtigt Dienste beim Abmelden), die Meldung weiterer Eigenschaften durch den Dienst (außer Farbe und Status), und das Einbetten von Diensten in den Hub. Der Hub gibt dafür noch keine Schnittstelle vor. Siehe `docs/OPEN-QUESTIONS.md`.
