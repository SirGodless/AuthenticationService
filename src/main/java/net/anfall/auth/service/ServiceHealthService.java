package net.anfall.auth.service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Prüft alle 60 Sekunden die Health-Adressen der Dienste und merkt sich das Ergebnis nur im Speicher (CLAUDE.md
 * Abschnitt 8). Nur eine Anzeige, es gibt keine Alarm-Mails. "Aktiv" heißt: Antwort mit Status 2xx oder 3xx innerhalb
 * von 5 Sekunden. Ohne Health-Adresse oder vor der ersten Prüfung ist der Status "unknown". Zusätzlich prüft der Dienst,
 * ob der Mailserver erreichbar ist (Anzeige nur für Admins).
 */
@Service
public class ServiceHealthService {

    private static final Logger log = LoggerFactory.getLogger(ServiceHealthService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** state: up, down oder unknown. */
    public record Status(String state, Instant checkedAt) {
        static final Status UNKNOWN = new Status("unknown", null);
    }

    private final ServiceRepository services;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            // Nur HTTP/1.1: Der Standard schickt bei http:// einen Upgrade-Wunsch (h2c) mit, und Dienste wie Next.js schließen darauf die Verbindung
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Status> results = new ConcurrentHashMap<>();
    /** Vom Dienst gemeldeter Status mit Ablaufzeit. Solange er gilt, hat er Vorrang vor der Abfrage. */
    private record Reported(Status status, Instant validUntil) {
    }

    private final Map<String, Reported> reported = new ConcurrentHashMap<>();
    private final String mailHost;
    private final int mailPort;
    private volatile Status mail = Status.UNKNOWN;

    public ServiceHealthService(ServiceRepository services, @Value("${spring.mail.host}") String mailHost,
                                @Value("${spring.mail.port}") int mailPort) {
        this.services = services;
        this.mailHost = mailHost;
        this.mailPort = mailPort;
    }

    public Status status(String serviceKey) {
        Reported r = reported.get(serviceKey);
        if (r != null && r.validUntil().isAfter(Instant.now())) {
            return r.status();
        }
        return results.getOrDefault(serviceKey, Status.UNKNOWN);
    }

    /** Der Dienst meldet seinen Status selbst (Dienst-Schnittstelle). Gilt für die angegebene Zeit, danach gilt wieder die Abfrage. */
    public void report(String serviceKey, String state, java.time.Duration validFor) {
        Instant now = Instant.now();
        reported.put(serviceKey, new Reported(new Status(state, now), now.plus(validFor)));
    }

    /** Vergisst alle Ergebnisse und Meldungen (die Status liegen nur im Speicher). Gedacht für Tests. */
    public void clearAll() {
        results.clear();
        reported.clear();
        mail = Status.UNKNOWN;
    }

    public Status mailStatus() {
        return mail;
    }

    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT15S")
    public void checkNow() {
        for (ServiceInfo s : services.findAll()) {
            if (s.healthUrl() == null) {
                results.remove(s.key());
                continue;
            }
            results.put(s.key(), new Status(isUp(s.healthUrl()) ? "up" : "down", Instant.now()));
        }
        mail = new Status(canConnect(mailHost, mailPort) ? "up" : "down", Instant.now());
    }

    private boolean isUp(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            int code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return code >= 200 && code < 400;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Statusprüfung fehlgeschlagen: {}", e.toString());
            return false;
        }
    }

    private static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
