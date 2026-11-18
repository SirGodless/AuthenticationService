package net.anfall.auth.invite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Ein Einladungslink wie in der Datenbank. status ist active, used oder deactivated (abgelaufen steht nicht in der Datenbank, siehe {@link #effectiveStatus}). */
public record InviteLink(UUID id, UUID creatorId, String creatorName, UUID roleId, String roleName,
                         List<Grant> rolePermissions, List<Grant> directPermissions, boolean privileged,
                         Instant createdAt, Instant expiresAt, String status, String statusReason,
                         String usedByName, String granted) {

    /** Status für die Anzeige: ein aktiver Link nach Ablauf heißt expired. */
    public String effectiveStatus() {
        return "active".equals(status) && !expiresAt.isAfter(Instant.now()) ? "expired" : status;
    }

    public boolean isOpen() {
        return "active".equals(effectiveStatus());
    }
}
