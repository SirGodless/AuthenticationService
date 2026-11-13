# Passwörter

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

Maßstab ist NIST SP 800-63B-4. Der Abgleich Anforderung für Anforderung steht in `docs/NIST-800-63B-4.md`. Der Code liegt in `src/main/java/net/anfall/auth/password/`.

## Was gilt

- **[E] Länge:** mindestens 15 Zeichen, keine Zusammensetzungsregeln. **[A]** Bis 128 Zeichen erlaubt (NIST verlangt mindestens 64). Gezählt werden Unicode-Codepoints, nicht UTF-16-Einheiten.
- **[E] Hashing:** Argon2id. Standard 64 MiB, 3 Durchläufe, Parallelität 1. Der Dienst startet nicht mit weniger als 19 MiB und 2 Durchläufen (OWASP-Mindestwerte). OWASP nennt auch den gleichwertigen Satz 46 MiB mit 1 Durchlauf. Der wird bewusst nicht erlaubt, weil Abschnitt 3 der Spezifikation 19 MiB und 2 Durchläufe als Untergrenze festlegt. Beim Start steht im Log, wie lange ein Hash dauert.
- **[E] Sperrliste mit Begründung:** Beim Anlegen, Ändern und Zurücksetzen wird das **ganze** Passwort (nicht Teile) gegen die Sperrliste und gegen Kontextwörter geprüft. Abgelehnt wird mit einem eigenen Grund (`password_blocked`, `password_context`), den die UI anzeigt, dazu eine Hilfestellung unter dem Feld.
- **[E] Austauschbare Schnittstelle:** `PasswordBlocklist`. Heute die NCSC-Liste aus einer Datei, später der Pwned-Passwords-Index.

## Korrektur gegenüber Etappe 2

In Etappe 2 hatte ich zwei Dinge aus dem Gedächtnis umgesetzt und NIST dafür zitiert. Beim Lesen des Originaltexts am 13.11.2026 (Quelle: `usnistgov/800-63-4`, veröffentlichte Fassung, Abschnitt 3.1.1.2) stellte sich heraus:

- **Normalisierung:** NIST nennt NFC, nicht NFKC. Das war falsch und ist korrigiert. NFC vereinheitlicht zusammengesetzte und zerlegte Zeichen (é), faltet aber keine Kompatibilitätszeichen (Ligaturen, volle Breite). Passwörter werden **nur** so verändert. Kein Trimmen, kein Kürzen.
- **Wiederholungen und Zeichenfolgen:** Die aktuelle Fassung nennt "sich wiederholende oder aufeinanderfolgende Zeichen" nicht. Das stammte aus älteren Fassungen. Ich hatte es in Etappe 2 nicht gebaut und baue es jetzt nicht nach, weil es nicht verlangt ist und leicht zu Fehlalarmen führt.

## Kontextwörter

Nach NIST sollen Dienstname, Username "and derivatives thereof" auf die Sperrliste. Umsetzung (`password/check/PasswordContext`):

- Wörter: Username, ganze E-Mail-Adresse, Teil vor dem @, Dienstname (`TOTP_ISSUER`, z. B. `anfall.net`), Host der Auth-Adresse (`auth.anfall.net`), `userhub`.
- **[A] "Ableitung" heißt hier:** Das ganze Passwort besteht, ohne Ziffern, Trennzeichen und Groß-/Kleinschreibung, aus nichts anderem als einem dieser Wörter, auch mehrfach hintereinander. Beispiel bei Username `joshua`: `Joshua-Joshua-2026-JOSHUA` wird abgelehnt, `joshua loves a long passphrase` bleibt erlaubt. Das passt zu NIST ("die gesamte Eingabe wird verglichen, nicht Teile oder darin enthaltene Wörter") und zu deiner Vorgabe, das ganze Passwort zu vergleichen.
- Wörter mit weniger als 3 Buchstaben werden ignoriert.

## Sperrliste

- NCSC-Liste (99.840 Einträge) aus SecLists, festgenagelter Commit mit SHA-256-Prüfsumme, wird beim Docker-Build per `scripts/fetch-blocklist.sh` geladen, nicht im Git. Mit und ohne Großschreibung abgelegt, damit `Password1234567890` und `password1234567890` dasselbe sind.
- NIST: Die Liste muss nicht riesig sein, weil sie nur Online-Raten abfangen soll, das ohnehin durch Limits gebremst wird. Bei uns sind das 5 Fehlversuche je 15 Minuten.
- **Ehrliche Einschätzung:** Von den 99.840 Einträgen sind nur 359 mindestens 15 Zeichen lang. Mit der 15-Zeichen-Regel fängt die Liste kaum noch etwas. NIST sagt dazu selbst, dass ein Wörterbuch nur Einträge in Mindestlänge braucht. Der Pwned-Passwords-Index würde mehr bringen.
- **Wörterbuchwörter:** NIST nennt sie als Beispiel für den Inhalt der Liste ("may include"). Eine Wortliste habe ich nicht eingebunden. Bei 15 Zeichen Mindestlänge ist ein einzelnes Wort kaum denkbar, Passphrasen aus mehreren Wörtern erlaubt NIST ausdrücklich.
- Fehlt die Datei, startet der Dienst nicht.

## Offen

- Pepper (zusätzlicher Schlüssel, den NIST mit SHOULD empfiehlt) ist nicht gebaut. Siehe `docs/OPEN-QUESTIONS.md`.
- Eine Stärkeanzeige gibt es nicht. NIST verlangt "Hilfestellung", keine Anzeige. Die Hilfestellung ist als Text unter den Passwortfeldern da.
