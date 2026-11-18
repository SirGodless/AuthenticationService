package net.anfall.auth.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anzeige-Eigenschaften der Dienste im Dashboard ändern (Recht admin.services.manage): Name, Beschreibung, Adresse zum
 * Öffnen, Akzentfarbe und Adresse für die Statusprüfung. Das Anlegen eines Dienstes samt OIDC-Client bleibt vorerst
 * beim Befehl register-service.
 */
@Service
public class ServiceAdminService {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Eingaben des Dashboards. Leere Texte bei Adressen und Farbe bedeuten "nicht gesetzt". */
    public record Update(String displayName, String description, String url, String accentColor, String healthUrl) {
    }

    private final ServiceRepository services;
    private final AuditLog audit;

    public ServiceAdminService(ServiceRepository services, AuditLog audit) {
        this.services = services;
        this.audit = audit;
    }

    public List<ServiceInfo> list() {
        return services.findAll();
    }

    @Transactional
    public void update(UUID actor, String key, Update u, String ip) {
        ServiceInfo old = services.findByKey(key).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "service_unknown"));
        String name = u.displayName() == null ? "" : u.displayName().trim();
        if (name.isEmpty() || name.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_name_invalid");
        }
        String description = u.description() == null ? "" : u.description().trim();
        if (description.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_description_invalid");
        }
        String url = cleanUrl(u.url(), "service_url_invalid");
        String healthUrl = cleanUrl(u.healthUrl(), "service_health_url_invalid");
        String color = cleanColor(u.accentColor());
        services.updateDisplay(key, name, description, url, color, healthUrl);
        audit.record(actor, null, "service.updated", Map.of("service", key, "accent_color", String.valueOf(color),
                "url_changed", !java.util.Objects.equals(old.url(), url), "health_url_changed", !java.util.Objects.equals(old.healthUrl(), healthUrl)), ip);
    }

    /** Nur http und https mit Host, höchstens 300 Zeichen. Leer heißt nicht gesetzt. */
    static String cleanUrl(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            URI uri = URI.create(v);
            boolean ok = v.length() <= 300 && uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
            if (!ok) {
                throw new ApiException(HttpStatus.BAD_REQUEST, errorCode);
            }
            return v;
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode);
        }
    }

    /** "#rrggbb", Groß- oder Kleinschreibung egal, gespeichert klein. Leer heißt keine eigene Farbe. */
    static String cleanColor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (!COLOR.matcher(v).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_color_invalid");
        }
        return v.toLowerCase();
    }
}
