package net.anfall.auth.permission;

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
 * Eigene Rechte anlegen und verwalten (Dashboard, Recht admin.permissions.manage). Wichtig für die Oberfläche:
 * Ein neues Recht hat nur Wirkung, wenn etwas es durchsetzt, nämlich ein Dienst, der es als Anmeldevoraussetzung
 * verlangt. Deshalb zeigt die Liste je Recht, welche Dienste es verlangen.
 */
@Service
public class PermissionDefinitionService {

    /** Name mit Bereich vor dem Punkt, z. B. "forum.moderate". */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    /** Recht mit seiner Verwendung für die Anzeige. */
    public record PermissionInfo(PermissionDefinition definition, List<String> enforcedByServices, long assignedCount) {
    }

    private final PermissionRepository repository;
    private final AuditLog audit;

    public PermissionDefinitionService(PermissionRepository repository, AuditLog audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<PermissionInfo> list() {
        return repository.findAll().stream().map(d -> new PermissionInfo(d, repository.servicesRequiring(d.key()),
                repository.valueCount(d.key()))).toList();
    }

    @Transactional
    public void create(UUID actor, PermissionDefinition d, String ip) {
        if (!KEY.matcher(d.key()).matches() || d.key().length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_key_invalid");
        }
        // admin.* sind die eingebauten Verwaltungsrechte, service.* entstehen automatisch mit einem Dienst
        if (d.key().startsWith("admin.") || d.key().startsWith("service.")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_key_reserved");
        }
        boolean number = d.isNumber();
        if (!number && !"boolean".equals(d.type())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_type_invalid");
        }
        if (number && !("higher".equals(d.direction()) || "lower".equals(d.direction()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "direction_required");
        }
        if (!number && (d.direction() != null || d.allowUnlimited())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "boolean_has_no_direction");
        }
        if (d.allowUnlimited() && !"higher".equals(d.direction())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unlimited_only_for_higher");
        }
        if (repository.find(d.key()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "permission_exists");
        }
        repository.insert(d);
        audit.record(actor, null, "permission.created", Map.of("permission", d.key(), "type", d.type()), ip);
    }

    /** Beschreibung und "per Link vergebbar" lassen sich ändern. Typ und Richtung nicht (die Werte hängen daran). */
    @Transactional
    public void update(UUID actor, String key, String description, boolean linkGrantable, String ip) {
        require(key);
        repository.update(key, description == null ? "" : description, linkGrantable);
        audit.record(actor, null, "permission.updated", Map.of("permission", key, "link_grantable", linkGrantable), ip);
    }

    @Transactional
    public void delete(UUID actor, String key, String ip) {
        PermissionDefinition d = require(key);
        if (d.builtin() || key.startsWith("service.")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_not_deletable");
        }
        if (repository.valueCount(key) > 0 || !repository.servicesRequiring(key).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "permission_in_use");
        }
        repository.delete(key);
        audit.record(actor, null, "permission.deleted", Map.of("permission", key), ip);
    }

    private PermissionDefinition require(String key) {
        return repository.find(key).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "permission_unknown"));
    }
}
