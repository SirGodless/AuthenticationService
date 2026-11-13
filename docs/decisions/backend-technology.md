# Backend: Technik und Versionen

Kennzeichnung: **[A]** Annahme, wartet auf Bestätigung. **[E]** von Joshua entschieden. **[U]** nicht oder nicht ganz verifiziert.

- **[A] Maven** als Build-Werkzeug. Die Cloud-Umgebung hat Maven 3.9 vorinstalliert, Joshua braucht nichts davon, weil der Docker-Build alles erledigt.
- **Versionen, geprüft gegen Maven Central am 12.11.2026:** Spring Boot **4.1.2** (letzte stabile 4.1.x), darin Spring Security **7.1.2**, Flyway 12.4.0, PostgreSQL-Treiber 42.7.14, Testcontainers 2.0.5. Java 21 (Boot 4 verlangt mindestens 17). BouncyCastle 1.87 wird von Hand festgelegt, weil Spring Security es für Argon2 braucht und es nicht im Boot-BOM steht.
- **Authorization Server:** Er ist in Spring Security 7 aufgegangen (`spring-security-oauth2-authorization-server`, Version 7.1.2 vorhanden). Er ist in Etappe 1 noch nicht eingebunden, dafür ist Etappe 3. **[U]** Auf Maven Central gibt es für Boot 4.1.2 zwei Starter, `spring-boot-starter-oauth2-authorization-server` und `spring-boot-starter-security-oauth2-authorization-server`. Die Spring-Doku nennt den ersten. Welcher der aktuelle und welcher ein Alias ist, kläre ich zu Beginn von Etappe 3.
- **[A] `JdbcClient` statt JPA/Hibernate.** SQL ist sichtbar und für Joshua lesbar, es gibt keine versteckte Magie. Die Datenmenge ist klein.
- **[A] PostgreSQL 17.11, Flyway-Migrationen** in `src/main/resources/db/migration`. Tests laufen gegen ein echtes PostgreSQL (Testcontainers), weil Sperren und Transaktionen sonst nicht echt geprüft werden.
- **[A] Health-Port 8081** (nur intern) für den Docker-Healthcheck. Caddy leitet nur `/api/*` und die OIDC-Pfade an das Backend, deshalb ist `/actuator` von außen nicht erreichbar.
- **[A] Fehler als Codes.** Die API liefert nur `{"error":"<code>"}`. Die Texte (Englisch und Deutsch) stehen in den Sprachdateien der UI. So steht kein Fließtext fest im Backend. Mails (ab Etappe 2) bekommen eigene Sprachdateien im Backend.
