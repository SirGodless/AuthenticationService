package net.anfall.auth.oidc;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Widerruft alle Autorisierungen eines Users (Refresh-Tokens, Codes). Aufgerufen bei Bann, Passwort-Reset und
 * 2FA-Reset. Bereits ausgestellte Access-Tokens bleiben bis zu ihrem Ablauf gültig (10 Minuten), das ist der Grund
 * für die kurze Laufzeit. Dienste prüfen sie meist ohnehin nur beim Login.
 */
@Service
public class TokenRevocationService {

    private final JdbcClient jdbc;

    public TokenRevocationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return Anzahl der gelöschten Autorisierungen */
    public int revokeAll(UUID userId) {
        return jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = :p")
                .param("p", userId.toString()).update();
    }
}
