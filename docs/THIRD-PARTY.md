# Fremdbestandteile und Lizenzen

Geprüft am 12.11.2026 jeweils am Original (Lizenzdatei oder Dateikopf).

| Bestandteil | Verwendung | Lizenz |
| :- | :- | :- |
| SecLists (Daniel Miessler), Datei `Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt` | Sperrliste für Passwörter, wird beim Docker-Build geladen, nicht im Git. Festgenagelt auf Commit `dbfb830d95e4f3da4fb7d7d277ed597de0ed7a18`, SHA-256 `c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0` | MIT (LICENSE im Repo). Die Liste selbst stammt vom UK NCSC, deren Bedingungen sind **nicht geprüft** |
| docker-postfix (`boky/postfix`) | Mailserver-Container | MIT (LICENSE im Repo) |
| qrcode-generator 2.0.4 (Kazuhiko Arase) | QR-Code der Authenticator-App, in der UI selbst gehostet | MIT (Dateikopf) |
| Spring Boot 4.1.2, Spring Security 7.1.2 (mit Authorization Server), Nimbus JOSE+JWT, Flyway, PostgreSQL-Treiber, BouncyCastle, GreenMail (nur Tests), Testcontainers (nur Tests) | Backend | Apache 2.0, bzw. nach den Angaben der jeweiligen Projekte (nicht einzeln geprüft) |
