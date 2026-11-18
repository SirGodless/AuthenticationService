package net.anfall.auth.invite;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import net.anfall.auth.audit.AuditLog;
import net.anfall.auth.settings.SettingsService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liest und ändert die Grenzen der Link-Gültigkeit und rechnet die Gültigkeit eines neuen Links aus. Grenzen gelten serverseitig. */
@Service
public class InviteLimitsService {

    private final SettingsService settings;
    private final AuditLog audit;

    public InviteLimitsService(SettingsService settings, AuditLog audit) {
        this.settings = settings;
        this.audit = audit;
    }

    public InviteLimits current() {
        InviteLimits d = InviteLimits.DEFAULTS;
        return new InviteLimits(settings.getInt("invite.min_hours", d.minHours()),
                settings.getInt("invite.default_days", d.defaultDays()),
                settings.getInt("invite.max_days", d.maxDays()),
                settings.getInt("invite.privileged_max_days", d.privilegedMaxDays()));
    }

    @Transactional
    public void update(UUID actor, InviteLimits limits, String ip) {
        long minHours = limits.minHours();
        boolean ok = limits.minHours() >= 1
                && limits.maxDays() >= 1 && limits.maxDays() <= InviteLimits.HARD_MAX_DAYS
                && limits.defaultDays() >= 1 && limits.defaultDays() <= limits.maxDays()
                && limits.privilegedMaxDays() >= 1 && limits.privilegedMaxDays() <= limits.maxDays()
                && limits.defaultDays() * 24L >= minHours && limits.privilegedMaxDays() * 24L >= minHours;
        if (!ok) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invite_limits_invalid");
        }
        settings.put("invite.min_hours", String.valueOf(limits.minHours()));
        settings.put("invite.default_days", String.valueOf(limits.defaultDays()));
        settings.put("invite.max_days", String.valueOf(limits.maxDays()));
        settings.put("invite.privileged_max_days", String.valueOf(limits.privilegedMaxDays()));
        audit.record(actor, null, "settings.invite_limits_changed", Map.of("min_hours", limits.minHours(),
                "default_days", limits.defaultDays(), "max_days", limits.maxDays(),
                "privileged_max_days", limits.privilegedMaxDays()), ip);
    }

    /** Gültigkeit eines neuen Links. requestedHours null = Standard. */
    public Duration validity(Integer requestedHours, boolean privileged) {
        InviteLimits limits = current();
        if (requestedHours == null) {
            return limits.standard(privileged);
        }
        Duration wanted = Duration.ofHours(requestedHours);
        if (wanted.compareTo(limits.min()) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validity_too_short");
        }
        if (wanted.compareTo(limits.max(privileged)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, privileged ? "validity_too_long_privileged" : "validity_too_long");
        }
        return wanted;
    }
}
