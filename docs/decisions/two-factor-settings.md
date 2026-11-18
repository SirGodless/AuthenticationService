# 2FA selbst verwalten (Etappe 4)

Kennzeichnung: **[E]** von Joshua entschieden. **[A]** Annahme, wartet auf Bestätigung.

Schnittstellen unter `/api/auth/2fa` (angemeldet), Seite `/account/2fa`. Code: `twofactor/TwoFactorSettingsService` und `TwoFactorController`.

## Ablauf

1. **Einrichten:** Passwort bestätigen (`POST /enrollment`), das Backend erzeugt ein Geheimnis und speichert es **verschlüsselt** in `totp_enrollments` (Migration V15, Schlüssel wie bei TOTP). Die Antwort enthält Geheimnis und Aussteller für den QR-Code. Das Geheimnis wird erst nach Schritt 2 zur 2FA.
2. **Bestätigen:** Code aus der App (`POST /enrollment/confirm`). Erst jetzt entstehen `user_totp` und **10 Wiederherstellungscodes**, die einmal angezeigt und nur gehasht gespeichert werden. **[A]** Die Vorbereitung gilt 15 Minuten.
3. **Codes erneuern:** Passwort und aktueller Code (oder ein Wiederherstellungscode). Die alten Codes gelten danach nicht mehr.
4. **Abschalten:** Passwort und aktueller Code. Beendet alle **anderen** Sitzungen. **[E]** Für User mit mindestens einem `admin.*`-Recht ist das gesperrt (403 `two_factor_required_for_admins`), die Seite blendet das Formular dann aus. Hat ein User noch kein `admin.*`-Recht und bekommt eines, bleibt seine 2FA bestehen und lässt sich nicht mehr abschalten.

## Absicherung

- **[A] Erneute Bestätigung:** Jede dieser Aktionen verlangt das Passwort (Einrichten, Erneuern, Abschalten) und, wo schon eine 2FA besteht, zusätzlich einen Code. Eine gestohlene Sitzung allein reicht also nicht, um die 2FA zu verändern.
- **[A] Limit:** 5 Fehlversuche je Konto in 15 Minuten, dann 15 Minuten Pause (eigener Zähler `reauth`, gleiche Werte wie beim Login). Jeder Fehlversuch wird protokolliert (`2fa.settings_failed`).
- Protokoll: `2fa.enabled`, `2fa.recovery_codes_regenerated`, `2fa.disabled`. Nie im Protokoll: Geheimnis und Codes.
- Der Code-Wiederverwendungsschutz (jedes TOTP-Zeitfenster gilt nur einmal) gilt auch hier.

## NIST

Die Wiederherstellungscodes werden nur in einer angemeldeten Sitzung ausgeliefert (siehe `docs/NIST-800-63B-4.md`, Zeile "Auslieferung in authentifizierter Sitzung").

Tests: `TwoFactorSettingsTest` (13 Fälle: Einrichten, falsche Eingaben, Codes gehasht, Login danach mit 2. Schritt, Erneuern, Abschalten und Sitzungsende, Admin darf nicht abschalten, Sperre nach Fehlversuchen, Protokoll ohne Geheimnisse).
