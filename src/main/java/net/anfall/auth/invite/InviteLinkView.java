package net.anfall.auth.invite;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Darstellung eines Links für die Oberfläche (ohne Token, den gibt es nach der Erstellung nicht mehr). */
public record InviteLinkView(UUID id, String creator, String role, List<Grant> permissions, List<Grant> rolePermissions,
                             boolean privileged, Instant createdAt, Instant expiresAt, String status, String statusReason,
                             String usedBy) {

    static InviteLinkView of(InviteLink l) {
        return new InviteLinkView(l.id(), l.creatorName(), l.roleName(), l.directPermissions(), l.rolePermissions(),
                l.privileged(), l.createdAt(), l.expiresAt(), l.effectiveStatus(), l.statusReason(), l.usedByName());
    }
}
