package net.anfall.auth.invite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.anfall.auth.permission.PermissionDefinition;
import net.anfall.auth.permission.PermissionRepository;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.role.Role;
import net.anfall.auth.role.RoleRepository;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Was ein Link vergeben darf (CLAUDE.md Abschnitt 4): Der Ersteller darf nur Rechte vergeben, die er selbst hat
 * (bei Zahlen: höchstens seinen Wert), und nur solche, die "per Link vergebbar" sind. Eine Rolle ist nur erlaubt,
 * wenn der Ersteller alle ihre Rechte hält und alle link-vergebbar sind. Dieselbe Prüfung läuft beim Erstellen und beim Einlösen.
 */
@Component
public class InviteGrantRules {

    /** Rolle mit dem Inhalt zum Zeitpunkt der Prüfung. */
    public record RoleSnapshot(Role role, List<Grant> permissions) {
    }

    private final PermissionService permissions;
    private final PermissionRepository permissionRepository;
    private final RoleRepository roles;

    public InviteGrantRules(PermissionService permissions, PermissionRepository permissionRepository, RoleRepository roles) {
        this.permissions = permissions;
        this.permissionRepository = permissionRepository;
        this.roles = roles;
    }

    /** Prüft direkt gewählte Rechte beim Erstellen. Wirft bei einem Verstoß. */
    public List<Grant> checkDirect(UUID creator, List<Grant> requested) {
        Map<String, PermissionValue> held = permissions.effective(creator);
        Set<String> seen = new HashSet<>();
        List<Grant> result = new ArrayList<>();
        for (Grant g : requested) {
            if (!seen.add(g.key())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "grant_duplicate");
            }
            requireGrantable(g.key());
            permissions.validate(g.key(), g.toValue());
            if (!covers(held, g)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "grant_not_held");
            }
            result.add(g);
        }
        return result;
    }

    /** Prüft eine Rolle beim Erstellen. */
    public RoleSnapshot checkRole(UUID creator, UUID roleId) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "role_unknown"));
        List<Grant> content = roleContent(role);
        Map<String, PermissionValue> held = permissions.effective(creator);
        for (Grant g : content) {
            requireGrantable(g.key());
            if (!covers(held, g)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "grant_not_held");
            }
        }
        return new RoleSnapshot(role, content);
    }

    /** Hält der Ersteller jetzt noch alles, auch den aktuellen Inhalt der Rolle? (Beim Einlösen.) */
    public boolean creatorStillHolds(UUID creator, UUID roleId, List<Grant> roleAtCreation, List<Grant> direct) {
        Map<String, PermissionValue> held = permissions.effective(creator);
        List<Grant> all = new ArrayList<>(direct);
        all.addAll(roleAtCreation);
        if (roleId != null) {
            Role role = roles.find(roleId).orElse(null);
            if (role == null) {
                return false;
            }
            try {
                for (Grant g : roleContent(role)) {
                    requireGrantable(g.key());
                    all.add(g);
                }
            } catch (ApiException e) {
                return false;
            }
        }
        return all.stream().allMatch(g -> covers(held, g));
    }

    /** Alle Rechte, die der Ersteller vergeben dürfte (für die Auswahl in der Oberfläche). */
    public List<PermissionDefinition> grantableBy(UUID creator) {
        Map<String, PermissionValue> held = permissions.effective(creator);
        return permissionRepository.findAll().stream()
                .filter(d -> d.linkGrantable() && !d.key().startsWith("admin."))
                .filter(d -> held.getOrDefault(d.key(), PermissionValue.NOT_SET).set()).toList();
    }

    public List<Role> grantableRoles(UUID creator) {
        return roles.findAll().stream().filter(r -> {
            try {
                checkRole(creator, r.id());
                return true;
            } catch (ApiException e) {
                return false;
            }
        }).toList();
    }

    // ---- Regeln ----

    /** admin.* wird nie per Link vergeben (die Rolle Admin ebenfalls nicht). */
    private void requireGrantable(String key) {
        PermissionDefinition d = permissionRepository.find(key)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "permission_unknown"));
        if (!d.linkGrantable() || key.startsWith("admin.")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_not_link_grantable");
        }
    }

    private List<Grant> roleContent(Role role) {
        if (role.allAdminPermissions()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "permission_not_link_grantable");
        }
        return permissionRepository.roleValues(role.id()).stream().map(r -> Grant.of(r.permissionKey(), r.value())).toList();
    }

    /** Deckt der Wert des Erstellers das Gewünschte ab? Höher gibt mehr: gewünscht höchstens so viel. Niedriger gibt mehr: gewünscht mindestens so viel. */
    boolean covers(Map<String, PermissionValue> held, Grant wanted) {
        PermissionValue mine = held.getOrDefault(wanted.key(), PermissionValue.NOT_SET);
        if (!mine.set()) {
            return false;
        }
        PermissionDefinition d = permissionRepository.find(wanted.key()).orElse(null);
        if (d == null || !d.isNumber()) {
            return true;
        }
        if (mine.unlimited()) {
            return true;
        }
        if (wanted.unlimited() || mine.number() == null || wanted.number() == null) {
            return false;
        }
        return "lower".equals(d.direction()) ? wanted.number() >= mine.number() : wanted.number() <= mine.number();
    }
}
