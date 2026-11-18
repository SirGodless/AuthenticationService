package net.anfall.auth.permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Berechtigungen eines Users. Die wirksamen Rechte werden bei jeder Abfrage aus allen Quellen neu berechnet
 * (Rollen sind Live-Gruppen, eine Änderung gilt sofort). Quellen: Rollen des Users, direkte Rechte und, für
 * Rechte mit Präfix "admin.", die Rolle, die alle Verwaltungsrechte enthält (die eingebaute Rolle Admin).
 * Die Rechenregeln stehen in {@link EffectivePermissionCalculator}.
 */
@Service
public class PermissionService {

    private final PermissionRepository repository;

    public PermissionService(PermissionRepository repository) {
        this.repository = repository;
    }

    /** Alle Rechte des Users mit wirksamem Wert, auch die nicht gesetzten (Schlüssel sortiert). */
    public Map<String, PermissionValue> effective(UUID userId) {
        Map<String, List<PermissionValue>> sources = new HashMap<>();
        repository.valuesFromRolesOf(userId).forEach(r -> sources.computeIfAbsent(r.permissionKey(), k -> new ArrayList<>()).add(r.value()));
        repository.userValues(userId).forEach(r -> sources.computeIfAbsent(r.permissionKey(), k -> new ArrayList<>()).add(r.value()));
        boolean allAdmin = repository.hasAllAdminRole(userId);
        Map<String, PermissionValue> result = new LinkedHashMap<>();
        for (PermissionDefinition d : repository.findAll()) {
            boolean implicit = allAdmin && d.key().startsWith("admin.") && !d.isNumber();
            result.put(d.key(), EffectivePermissionCalculator.combine(d, sources.getOrDefault(d.key(), List.of()), implicit));
        }
        return result;
    }

    /** Hat der User dieses (boolesche oder Zahlen-)Recht gesetzt? */
    public boolean has(UUID userId, String permissionKey) {
        return effective(userId).getOrDefault(permissionKey, PermissionValue.NOT_SET).set();
    }

    public PermissionValue value(UUID userId, String permissionKey) {
        return effective(userId).getOrDefault(permissionKey, PermissionValue.NOT_SET);
    }

    /** Hat der User mindestens ein Verwaltungsrecht (admin.*)? Dann ist die 2FA Pflicht. */
    public boolean hasAnyAdminPermission(UUID userId) {
        return effective(userId).entrySet().stream().anyMatch(e -> e.getKey().startsWith("admin.") && e.getValue().set());
    }

    // ---- direkt vergebene Rechte ----

    public void grantToUser(UUID userId, String permissionKey) {
        setUserValue(userId, permissionKey, PermissionValue.GRANTED);
    }

    public void revokeFromUser(UUID userId, String permissionKey) {
        repository.removeUserValue(userId, permissionKey);
    }

    /** Setzt den direkten Wert eines Rechts bei einem User. Der Wert muss zum Typ des Rechts passen. */
    public void setUserValue(UUID userId, String permissionKey, PermissionValue value) {
        validate(permissionKey, value);
        repository.setUserValue(userId, permissionKey, value);
    }

    /** Prüft, dass der Wert zum Typ des Rechts passt (gemeinsam für Rollen und User). */
    public void validate(String permissionKey, PermissionValue value) {
        PermissionDefinition d = repository.find(permissionKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "permission_unknown"));
        if (!d.isNumber()) {
            if (value.number() != null || value.unlimited()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "value_not_allowed_for_boolean");
            }
            return;
        }
        boolean unlimitedOk = d.allowUnlimited() && "higher".equals(d.direction());
        if (value.unlimited() && !unlimitedOk) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "unlimited_not_allowed");
        }
        if (value.unlimited() == (value.number() != null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "number_or_unlimited_required");
        }
        if (value.number() != null && value.number() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "number_negative");
        }
    }
}
