package net.anfall.auth.role;

import java.util.UUID;

/** Eine Rolle (Live-Gruppe). allAdminPermissions: Sie enthält alle admin.*-Rechte (die eingebaute Rolle Admin). */
public record Role(UUID id, String name, boolean builtin, boolean allAdminPermissions) {
}
