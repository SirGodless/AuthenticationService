package net.anfall.auth.invite;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.invite.InviteGrantRules.RoleSnapshot;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Einladungslinks erstellen, auflisten und deaktivieren. Der Token wird nur einmal bei der Erstellung gezeigt, gespeichert ist nur sein Hash. */
@Service
public class InviteLinkService {

    /** Antwort beim Erstellen. Der Token steht nirgends sonst (nicht im Protokoll, nicht in der Datenbank). */
    public record Created(UUID id, String url, Instant expiresAt) {
    }

    /** Was der Ersteller im Formular wählen kann. */
    public record Options(boolean canCreate, long activeCount, PermissionValue maxActive, InviteLimits limits,
                          List<net.anfall.auth.permission.PermissionDefinition> grantablePermissions,
                          List<net.anfall.auth.role.Role> grantableRoles) {
    }

    private final InviteLinkRepository links;
    private final InviteGrantRules rules;
    private final InviteLimitsService limits;
    private final PermissionService permissions;
    private final AuditLog audit;
    private final AppProperties props;

    public InviteLinkService(InviteLinkRepository links, InviteGrantRules rules, InviteLimitsService limits,
                             PermissionService permissions, AuditLog audit, AppProperties props) {
        this.links = links;
        this.rules = rules;
        this.limits = limits;
        this.permissions = permissions;
        this.audit = audit;
        this.props = props;
    }

    @Transactional
    public Created create(UUID creator, Integer validityHours, UUID roleId, List<Grant> direct, String ip) {
        links.lockCreator(creator);
        if (!permissions.has(creator, "links.create")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        }
        // "Nicht gesetzt" heißt: kein Platz. Unbegrenzt hebt das Limit auf.
        PermissionValue max = permissions.value(creator, "links.max_active");
        if (!max.unlimited() && links.activeCount(creator) >= (max.number() == null ? 0 : max.number())) {
            throw new ApiException(HttpStatus.CONFLICT, "link_limit_reached");
        }
        RoleSnapshot role = roleId == null ? null : rules.checkRole(creator, roleId);
        List<Grant> grants = rules.checkDirect(creator, direct == null ? List.of() : direct);
        boolean privileged = role != null || !grants.isEmpty();
        Duration validity = limits.validity(validityHours, privileged);

        String token = Tokens.newToken();
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(validity);
        links.insert(id, Tokens.sha256Hex(token), creator, role == null ? null : role.role().id(),
                role == null ? null : role.role().name(), role == null ? List.of() : role.permissions(), grants, privileged, expiresAt);
        audit.record(creator, null, "invite.created", Map.of("link", id.toString(), "privileged", privileged,
                "expires_at", expiresAt.toString()), ip);
        return new Created(id, props.publicBaseUrl() + "/invite/" + token, expiresAt);
    }

    public Options options(UUID creator) {
        boolean can = permissions.has(creator, "links.create");
        return new Options(can, links.activeCount(creator), permissions.value(creator, "links.max_active"), limits.current(),
                can ? rules.grantableBy(creator) : List.of(), can ? rules.grantableRoles(creator) : List.of());
    }

    public List<InviteLink> listOwn(UUID creator) {
        return links.listByCreator(creator);
    }

    public List<InviteLink> listAll() {
        return links.listAll();
    }

    /** Eigenen Link deaktivieren. */
    @Transactional
    public void deactivateOwn(UUID creator, UUID linkId, String ip) {
        InviteLink link = links.findById(linkId).filter(l -> l.creatorId().equals(creator))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "link_unknown"));
        deactivate(creator, link, "manual", ip);
    }

    /** Irgendeinen Link deaktivieren (Admin). */
    @Transactional
    public void deactivateAny(UUID actor, UUID linkId, String ip) {
        InviteLink link = links.findById(linkId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "link_unknown"));
        deactivate(actor, link, "admin", ip);
    }

    private void deactivate(UUID actor, InviteLink link, String reason, String ip) {
        if (!links.deactivate(link.id(), reason)) {
            throw new ApiException(HttpStatus.CONFLICT, "link_not_active");
        }
        audit.record(actor, link.creatorId(), "invite.deactivated", Map.of("link", link.id().toString(), "reason", reason), ip);
    }

    /** Beim Bann des Erstellers: alle offenen Links deaktivieren. Bereits entstandene Konten bleiben unberührt. */
    @Transactional
    public int deactivateAllOf(UUID creator, UUID actor, String ip) {
        int count = links.deactivateAllActiveOf(creator, "creator_banned");
        if (count > 0) {
            audit.record(actor, creator, "invite.deactivated_on_ban", Map.of("count", count), ip);
        }
        return count;
    }
}
