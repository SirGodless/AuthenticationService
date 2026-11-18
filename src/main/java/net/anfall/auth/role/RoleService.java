package net.anfall.auth.role;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.LastAdminRule;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.permission.PermissionRepository;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rollen verwalten (Dashboard, Recht admin.roles.manage). Rollen sind Live-Gruppen: Ändert sich eine Rolle, gilt das
 * sofort für alle Mitglieder, weil die wirksamen Rechte bei jeder Abfrage neu berechnet werden.
 */
@Service
public class RoleService {

    /** Rolle mit ihren Rechten und der Zahl der Mitglieder für die Anzeige. */
    public record RoleInfo(Role role, List<PermissionRepository.ValueRow> permissions, long members) {
    }

    private final RoleRepository roles;
    private final PermissionRepository permissionRepository;
    private final PermissionService permissions;
    private final LastAdminRule lastAdmin;
    private final AuditLog audit;

    public RoleService(RoleRepository roles, PermissionRepository permissionRepository, PermissionService permissions,
                       LastAdminRule lastAdmin, AuditLog audit) {
        this.roles = roles;
        this.permissionRepository = permissionRepository;
        this.permissions = permissions;
        this.lastAdmin = lastAdmin;
        this.audit = audit;
    }

    public List<RoleInfo> list() {
        return roles.findAll().stream()
                .map(r -> new RoleInfo(r, permissionRepository.roleValues(r.id()), roles.memberCount(r.id()))).toList();
    }

    @Transactional
    public UUID create(UUID actor, String name, String ip) {
        String clean = cleanName(name);
        if (roles.nameExists(clean, null)) {
            throw new ApiException(HttpStatus.CONFLICT, "role_exists");
        }
        UUID id = UUID.randomUUID();
        roles.insert(id, clean);
        audit.record(actor, null, "role.created", Map.of("role", clean), ip);
        return id;
    }

    @Transactional
    public void rename(UUID actor, UUID roleId, String name, String ip) {
        Role role = editable(roleId);
        String clean = cleanName(name);
        if (roles.nameExists(clean, roleId)) {
            throw new ApiException(HttpStatus.CONFLICT, "role_exists");
        }
        roles.rename(roleId, clean);
        audit.record(actor, null, "role.renamed", Map.of("from", role.name(), "to", clean), ip);
    }

    @Transactional
    public void delete(UUID actor, UUID roleId, String ip) {
        Role role = editable(roleId);
        roles.delete(roleId);
        audit.record(actor, null, "role.deleted", Map.of("role", role.name()), ip);
    }

    /** Setzt ein Recht in einer Rolle (boolesch: vergeben, Zahl: Wert oder unbegrenzt). */
    @Transactional
    public void setPermission(UUID actor, UUID roleId, String key, PermissionValue value, String ip) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "role_unknown"));
        rejectImplicitAdminRights(role, key);
        permissions.validate(key, value);
        permissionRepository.setRoleValue(roleId, key, value);
        audit.record(actor, null, "role.permission_set", Map.of("role", role.name(), "permission", key), ip);
    }

    @Transactional
    public void removePermission(UUID actor, UUID roleId, String key, String ip) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "role_unknown"));
        rejectImplicitAdminRights(role, key);
        permissionRepository.removeRoleValue(roleId, key);
        audit.record(actor, null, "role.permission_removed", Map.of("role", role.name(), "permission", key), ip);
    }

    @Transactional
    public void assign(UUID actor, UUID userId, UUID roleId, String ip) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "role_unknown"));
        roles.assign(userId, roleId);
        audit.record(actor, userId, "role.assigned", Map.of("role", role.name()), ip);
    }

    /** Entzieht eine Rolle. Die Rolle Admin lässt sich dem letzten Admin nicht entziehen. */
    @Transactional
    public void unassign(UUID actor, UUID userId, UUID roleId, String ip) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "role_unknown"));
        if (role.allAdminPermissions() && lastAdmin.isLastAdmin(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "cannot_remove_last_admin");
        }
        roles.unassign(userId, roleId);
        audit.record(actor, userId, "role.unassigned", Map.of("role", role.name()), ip);
    }

    /** Eingebaute Rollen lassen sich nicht umbenennen oder löschen. */
    private Role editable(UUID roleId) {
        Role role = roles.find(roleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "role_unknown"));
        if (role.builtin()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role_builtin");
        }
        return role;
    }

    /** Die Rolle Admin enthält alle admin.*-Rechte ohnehin. Einträge dafür wären doppelt und irreführend. */
    private static void rejectImplicitAdminRights(Role role, String key) {
        if (role.allAdminPermissions() && key.startsWith("admin.")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role_has_all_admin_permissions");
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.length() < 2 || clean.length() > 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "role_name_invalid");
        }
        return clean;
    }
}
