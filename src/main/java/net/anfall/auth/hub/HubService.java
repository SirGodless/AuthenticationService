package net.anfall.auth.hub;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.invite.InviteLinkService;
import net.anfall.auth.login.session.AccountPrincipal;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.permission.PermissionValue;
import net.anfall.auth.security.AdminAccessGuard;
import net.anfall.auth.service.ServiceHealthService;
import net.anfall.auth.service.ServiceRepository;
import org.springframework.stereotype.Service;

/**
 * Alles, was der Hub nach dem Login anzeigt: die Dienste, die der User nutzen darf (er braucht das Recht des Dienstes,
 * die Entscheidung fällt hier und nicht in der Oberfläche), ihr Status und ein paar Angaben zum eigenen Konto.
 */
@Service
public class HubService {

    /** Ein Dienst für die Anzeige. status: up, down oder unknown. */
    public record HubServiceView(String key, String name, String description, String url, String openMode,
                                 String accentColor, String status, Instant checkedAt) {
    }

    public record MailView(String status, Instant checkedAt) {
    }

    public record AccountView(String username, String language, String theme, boolean twoFactorEnabled, boolean isAdmin,
                              boolean canCreateLinks, long activeLinks, PermissionValue maxActiveLinks) {
    }

    public record Hub(AccountView account, List<HubServiceView> services, MailView mail) {
    }

    private final ServiceRepository services;
    private final ServiceHealthService health;
    private final PermissionService permissions;
    private final AdminAccessGuard guard;
    private final InviteLinkService invites;
    private final AccountRepository accounts;

    public HubService(ServiceRepository services, ServiceHealthService health, PermissionService permissions,
                      AdminAccessGuard guard, InviteLinkService invites, AccountRepository accounts) {
        this.services = services;
        this.health = health;
        this.permissions = permissions;
        this.guard = guard;
        this.invites = invites;
        this.accounts = accounts;
    }

    public Hub forUser(AccountPrincipal me) {
        UUID id = me.id();
        var effective = permissions.effective(id);
        List<HubServiceView> visible = services.findAll().stream()
                .filter(s -> effective.getOrDefault(s.requiredPermission(), PermissionValue.NOT_SET).set())
                .map(s -> {
                    var status = health.status(s.key());
                    return new HubServiceView(s.key(), s.displayName(), s.description(), s.url(), s.openMode(),
                            s.effectiveAccentColor(), status.state(), status.checkedAt());
                }).toList();
        boolean isAdmin = effective.entrySet().stream().anyMatch(e -> e.getKey().startsWith("admin.") && e.getValue().set());
        var links = invites.options(id);
        // Der Status des Mailservers ist nur für Admins sichtbar
        MailView mail = isAdmin ? new MailView(health.mailStatus().state(), health.mailStatus().checkedAt()) : null;
        return new Hub(new AccountView(me.username(), accounts.language(id), accounts.theme(id), guard.hasTwoFactor(id), isAdmin, links.canCreate(),
                links.activeCount(), links.maxActive()), visible, mail);
    }
}
