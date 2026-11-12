package net.anfall.auth.security;

import java.util.UUID;

/** Der angemeldete User, wie ihn Spring Security kennt. Rechte werden ab Etappe 4 ausgewertet. */
public record AccountPrincipal(UUID id, String username) {
}
