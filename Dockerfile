# Stufe 1: Bauen
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
# Abhängigkeiten zuerst laden, damit Änderungen am Code nicht alles neu laden
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Stufe 2: Laufzeit, ohne Root-Rechte
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
# Sperrliste bekannter Passwörter laden (festgenagelte Version mit Prüfsumme, nicht im Git)
COPY scripts/fetch-blocklist.sh /tmp/fetch-blocklist.sh
RUN sh /tmp/fetch-blocklist.sh /app/data && rm /tmp/fetch-blocklist.sh
COPY scripts/auth-admin /usr/local/bin/auth-admin
COPY --from=build /build/target/auth-backend.jar app.jar
USER 10001:10001
EXPOSE 8080 8081
# Healthcheck auf dem internen Management-Port (wird von Caddy nicht veröffentlicht)
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
