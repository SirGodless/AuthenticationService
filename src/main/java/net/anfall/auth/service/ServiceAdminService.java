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
 * Dienste im Dashboard verwalten (Recht admin.services.manage): anlegen (mit OIDC-Client und Recht), Anzeige-Eigenschaften
 * (Name, Beschreibung, Adresse, Akzentfarbe, Statusadresse), Rücksprung-Adressen und neues Client-Geheimnis.
 * Das Client-Geheimnis steht nur in der Antwort auf Anlegen und Erneuern, danach nirgends mehr im Klartext.
 */
@Service
public class ServiceAdminService {

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** Eingaben des Dashboards. Leere Texte bei Adressen und Farbe bedeuten "nicht gesetzt". */
    public record Update(String displayName, String description, String url, String accentColor, Boolean accentLocked,
                         String healthUrl) {
    }

    /** Eingaben zum Anlegen. Alles außer Schlüssel, Name und Rücksprung-Adressen ist optional. */
    public record Create(String key, String displayName, List<String> redirectUris, String description, String url,
                         String accentColor, String healthUrl) {
    }

    /** Der neue Dienst und sein Client-Geheimnis im Klartext (wird nur jetzt angezeigt). */
    public record Created(ServiceAdminView service, String clientSecret) {
    }

    private final ServiceRepository services;
    private final ServiceRegistrationService registration;
    private final AuditLog audit;

    public ServiceAdminService(ServiceRepository services, ServiceRegistrationService registration, AuditLog audit) {
        this.services = services;
        this.registration = registration;
        this.audit = audit;
    }

    public List<ServiceAdminView> list() {
        return services.findAll().stream().map(s -> ServiceAdminView.of(s, registration.redirectUrisOf(s.key()))).toList();
    }

    @Transactional
    public Created create(UUID actor, Create c, String ip) {
        // Zusatzfelder zuerst prüfen: Bei einem Fehler soll nichts halb angelegt sein
        String description = c.description() == null ? "" : c.description().trim();
        if (description.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_description_invalid");
        }
        String url = cleanUrl(c.url(), "service_url_invalid");
        String healthUrl = cleanUrl(c.healthUrl(), "service_health_url_invalid");
        String color = cleanColor(c.accentColor());
        String name = c.displayName() == null ? "" : c.displayName().trim();
        if (name.isEmpty() || name.length() > 60) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "service_name_invalid");
        }
        var registered = registration.register(c.key() == null ? null : c.key().trim(), name, c.redirectUris());
        String key = registered.service().key();
        services.updateDisplay(key, name, description, url, color, false, healthUrl);
        audit.record(actor, null, "service.created", Map.of("service", key), ip);
        ServiceInfo info = services.findByKey(key).orElseThrow();
        return new Created(ServiceAdminView.of(info, registration.redirectUrisOf(key)), registered.clientSecret());
    }

    @Transactional
    public void setRedirectUris(UUID actor, String key, List<String> redirectUris, String ip) {
        registration.replaceRedirectUris(key, redirectUris);
        audit.record(actor, null, "service.redirect_uris_changed", Map.of("service", key, "count", redirectUris.size()), ip);
    }

    @Transactional
    public String regenerateSecret(UUID actor, String key, String ip) {
        String secret = registration.regenerateSecret(key);
        audit.record(actor, null, "service.secret_regenerated", Map.of("service", key), ip);
        return secret;
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
        boolean locked = Boolean.TRUE.equals(u.accentLocked());
        services.updateDisplay(key, name, description, url, color, locked, healthUrl);
        audit.record(actor, null, "service.updated", Map.of("service", key, "accent_color", String.valueOf(color), "accent_locked", locked,
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
