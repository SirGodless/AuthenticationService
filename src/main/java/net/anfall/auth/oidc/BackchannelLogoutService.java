package net.anfall.auth.oidc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.service.ServiceInfo;
import net.anfall.auth.service.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gemeinsames Abmelden (OpenID Connect Back-Channel Logout): Endet eine Anmeldung im Hub, schickt der Hub jedem Dienst,
 * bei dem sich der User damit angemeldet hat, ein signiertes Logout-Token (POST, Feld logout_token). Der Dienst beendet
 * daraufhin seine Sitzung. Zusätzlich werden die Autorisierungen (Refresh-Tokens) gelöscht, damit sich der Dienst nicht
 * stillschweigend wieder anmelden kann.
 *
 * Zugestellt wird einmal, im Hintergrund, mit kurzem Zeitlimit. Schlägt das fehl, bleibt es bei der Absicherung über
 * den gelöschten Refresh-Token (der Dienst verliert seine Sitzung spätestens beim nächsten Refresh).
 */
@Service
public class BackchannelLogoutService {

    private static final Logger LOG = LoggerFactory.getLogger(BackchannelLogoutService.class);
    static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
    private static final Duration TOKEN_LIFETIME = Duration.ofSeconds(120);

    private final JdbcClient jdbc;
    private final ServiceRepository services;
    private final JwtEncoder encoder;
    private final AuditLog audit;
    private final String issuer;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            // Nur HTTP/1.1: Der Standard schickt bei http:// einen Upgrade-Wunsch (h2c) mit, und Dienste wie Next.js schließen darauf die Verbindung
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BackchannelLogoutService(JdbcClient jdbc, ServiceRepository services, JwtEncoder encoder, AuditLog audit,
                                    AppProperties props) {
        this.jdbc = jdbc;
        this.services = services;
        this.encoder = encoder;
        this.audit = audit;
        this.issuer = props.publicBaseUrl();
    }

    /** Beendet die Dienst-Sitzungen, die aus genau einer Hub-Sitzung stammen (normales Abmelden). @return Anzahl gelöschter Autorisierungen */
    public int endSession(UUID userId, UUID sid) {
        return end(userId, sid);
    }

    /** Beendet alle Dienst-Sitzungen eines Users (Bann, Passwort-Reset, 2FA-Reset). @return Anzahl gelöschter Autorisierungen */
    public int endAllSessions(UUID userId) {
        return end(userId, null);
    }

    private int end(UUID userId, UUID sid) {
        String like = sid == null ? "%" : "%" + OidcSecurityContextRepository.SID_PREFIX + sid + "%";
        var rows = jdbc.sql("""
                        SELECT a.id, c.client_id FROM oauth2_authorization a
                        JOIN oauth2_registered_client c ON c.id = a.registered_client_id
                        WHERE a.principal_name = :p AND a.attributes LIKE :like
                        """)
                .param("p", userId.toString()).param("like", like)
                .query((rs, n) -> Map.entry(rs.getString("id"), rs.getString("client_id"))).list();
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.sql("DELETE FROM oauth2_authorization WHERE id IN (:ids)").param("ids", rows.stream().map(Map.Entry::getKey).toList()).update();
        Set<String> clientIds = new LinkedHashSet<>();
        rows.forEach(r -> clientIds.add(r.getValue()));
        // Erst nach dem Commit benachrichtigen: Bei einem Rollback soll kein Dienst abgemeldet werden
        Runnable notify = () -> clientIds.forEach(clientId -> services.findByClientId(clientId)
                .filter(s -> s.backchannelLogoutUri() != null)
                .ifPresent(s -> executor.submit(() -> deliver(s, userId, sid))));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notify.run();
                }
            });
        } else {
            notify.run();
        }
        return rows.size();
    }

    private void deliver(ServiceInfo service, UUID userId, UUID sid) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("service", service.key());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(service.backchannelLogoutUri()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("logout_token="
                            + URLEncoder.encode(logoutToken(service.clientId(), userId, sid), StandardCharsets.UTF_8)))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            details.put("status", status);
            details.put("ok", status == 200 || status == 204);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            details.put("ok", false);
            details.put("error", e.getClass().getSimpleName());
            LOG.warn("Logout-Token an Dienst {} nicht zugestellt: {}", service.key(), e.getClass().getSimpleName());
        }
        try {
            audit.record(null, userId, "service.backchannel_logout", details, null);
        } catch (RuntimeException e) {
            LOG.warn("Protokolleintrag zum Logout-Token fehlgeschlagen: {}", e.getClass().getSimpleName());
        }
    }

    /** Das Logout-Token: iss, sub, aud, iat, exp, jti, events und (bei Sitzungsende) sid. Kein nonce. */
    String logoutToken(String clientId, UUID userId, UUID sid) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString())
                .audience(List.of(clientId)).issuedAt(now).expiresAt(now.plus(TOKEN_LIFETIME)).id(UUID.randomUUID().toString())
                .claim("events", Map.of(EVENT, Map.of()));
        if (sid != null) {
            claims.claim("sid", sid.toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("logout+jwt").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
