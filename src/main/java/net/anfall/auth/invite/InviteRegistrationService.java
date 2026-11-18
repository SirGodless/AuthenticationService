package net.anfall.auth.invite;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.account.AccountContact;
import net.anfall.auth.account.AccountRepository;
import net.anfall.auth.account.AccountService;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.crypto.Tokens;
import net.anfall.auth.emailconfirmation.EmailConfirmationService;
import net.anfall.auth.permission.PermissionRepository;
import net.anfall.auth.role.RoleRepository;
import net.anfall.auth.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registrierung über einen Einladungslink (CLAUDE.md Abschnitt 4). Der Link wird beim Absenden des Formulars verbraucht,
 * nicht beim Öffnen (Mail- und Messenger-Vorschauen öffnen Links von selbst). Das Einlösen ist atomar: Die Zeile des Links
 * ist bis zum Ende der Transaktion gesperrt, ein zweites gleichzeitiges Absenden sieht danach "benutzt".
 * Jeder Fehler am Link (unbekannt, abgelaufen, benutzt, deaktiviert, Ersteller gebannt oder ohne Rechte) gibt dieselbe neutrale Antwort.
 */
@Service
public class InviteRegistrationService {

    private final InviteLinkRepository links;
    private final InviteGrantRules rules;
    private final InviteIpBlockService ipBlocks;
    private final AccountService accounts;
    private final AccountRepository accountRepository;
    private final EmailConfirmationService emailConfirmation;
    private final RoleRepository roles;
    private final PermissionRepository permissionRepository;
    private final AuditLog audit;
    private final JsonMapper json;

    public InviteRegistrationService(InviteLinkRepository links, InviteGrantRules rules, InviteIpBlockService ipBlocks,
                                     AccountService accounts, AccountRepository accountRepository,
                                     EmailConfirmationService emailConfirmation, RoleRepository roles,
                                     PermissionRepository permissionRepository, AuditLog audit, JsonMapper json) {
        this.links = links;
        this.rules = rules;
        this.ipBlocks = ipBlocks;
        this.accounts = accounts;
        this.accountRepository = accountRepository;
        this.emailConfirmation = emailConfirmation;
        this.roles = roles;
        this.permissionRepository = permissionRepository;
        this.audit = audit;
        this.json = json;
    }

    /** Die Seite fragt, ob der Link noch gilt. Verbraucht ihn nicht. */
    public boolean isValid(String token, String ip) {
        ipBlocks.requireNotBlocked(ip);
        String hash = Tokens.sha256Hex(token);
        InviteLink link = links.findByTokenHash(hash).orElse(null);
        if (link == null) {
            ipBlocks.recordUnknownLink(ip);
            return false;
        }
        return usable(link);
    }

    public record Registered(UUID userId) {
    }

    /** Legt das Konto an und verbraucht den Link. Formularfehler (Username, Passwort, ...) lassen den Link unverbraucht. */
    @Transactional
    public Registered register(String token, String username, String email, String password, String language, String ip) {
        ipBlocks.requireNotBlocked(ip);
        String hash = Tokens.sha256Hex(token);
        InviteLink link = links.lockByTokenHash(hash).orElse(null);
        if (link == null) {
            ipBlocks.recordUnknownLink(ip);
            throw invalid();
        }
        if (!usable(link)) {
            throw invalid();
        }
        AccountService.NewAccount account = accounts.validate(username, email, password, language);
        UUID userId;
        try {
            userId = accounts.create(account, false, link.creatorId());
        } catch (DuplicateKeyException e) {
            // Ein gleichzeitiger zweiter Link war schneller: gleicher Formularfehler wie sonst
            throw new ApiException(HttpStatus.CONFLICT, accountRepository.usernameExists(account.username()) ? "username_taken" : "email_taken");
        }
        if (link.roleId() != null) {
            roles.assign(userId, link.roleId());
        }
        for (Grant g : link.directPermissions()) {
            permissionRepository.setUserValue(userId, g.key(), g.toValue());
        }
        Map<String, Object> granted = new LinkedHashMap<>();
        granted.put("role", link.roleName());
        granted.put("permissions", link.directPermissions());
        links.markUsed(link.id(), userId, json.writeValueAsString(granted));
        emailConfirmation.sendFor(userId, account.email(), account.language(), account.username());
        audit.record(userId, userId, "account.registered", Map.of("link", link.id().toString(),
                "invited_by", link.creatorId().toString(), "role", String.valueOf(link.roleName())), ip);
        return new Registered(userId);
    }

    /**
     * Gilt der Link jetzt? Aktiv und nicht abgelaufen, Ersteller nicht gebannt und hält noch alle Rechte (auch den
     * aktuellen Inhalt der Rolle). Bei einem Verstoß wird der ganze Link abgelehnt.
     */
    private boolean usable(InviteLink link) {
        if (!link.status().equals("active") || !link.expiresAt().isAfter(Instant.now())) {
            return false;
        }
        boolean creatorOk = accountRepository.findContactById(link.creatorId()).map(c -> !c.banned()).orElse(false);
        return creatorOk && rules.creatorStillHolds(link.creatorId(), link.roleId(), link.rolePermissions(), link.directPermissions());
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.NOT_FOUND, "invalid_link");
    }
}
