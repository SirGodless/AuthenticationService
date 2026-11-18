package net.anfall.auth.security;

import java.util.UUID;
import net.anfall.auth.permission.PermissionService;
import net.anfall.auth.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Wer eine Admin-Funktion nutzen will, braucht dafür das Recht UND eine eingerichtete 2FA (CLAUDE.md Abschnitt 3:
 * "Die Pflicht greift vor jeder Admin-Funktion"). Ohne das Recht antwortet die API mit "forbidden", ohne Hinweis
 * darauf, was es sonst gäbe. Hat jemand das Recht, aber keine 2FA, bekommt er "two_factor_required" und die UI schickt ihn
 * zur Einrichtung. Die Entscheidung trifft immer das Backend, die UI blendet nur Menüs ein.
 */
@Component
public class AdminAccessGuard {

    private final PermissionService permissions;
    private final JdbcClient jdbc;

    public AdminAccessGuard(PermissionService permissions, JdbcClient jdbc) {
        this.permissions = permissions;
        this.jdbc = jdbc;
    }

    public void require(UUID userId, String permission) {
        if (!permissions.has(userId, permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        }
        if (!hasTwoFactor(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "two_factor_required");
        }
    }

    /** Wie {@link #require}, aber ein Recht aus der Liste genügt (z. B. zum Lesen der Rechteliste in mehreren Dialogen). */
    public void requireAny(UUID userId, String... anyOf) {
        for (String permission : anyOf) {
            if (permissions.has(userId, permission)) {
                require(userId, permission);
                return;
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
    }

    public boolean hasTwoFactor(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_totp WHERE user_id = :u)").param("u", userId)
                .query(Boolean.class).single();
    }
}
