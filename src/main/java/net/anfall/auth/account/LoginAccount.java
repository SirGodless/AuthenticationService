package net.anfall.auth.account;

import java.util.UUID;

/** Was der Login über ein Konto wissen muss. */
public record LoginAccount(UUID id, String username, String language, boolean banned, boolean emailVerified,
                           String passwordHash, boolean hasTotp) {
}
