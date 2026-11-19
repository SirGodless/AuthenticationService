package net.anfall.auth.oidc;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Widerruft alle Autorisierungen eines Users (Refresh-Tokens, Codes) und meldet ihn bei den Diensten ab. Aufgerufen bei
 * Bann, Passwort-Reset und 2FA-Reset. Bereits ausgestellte Access-Tokens bleiben bis zu ihrem Ablauf gültig (10 Minuten), das ist der Grund
 * für die kurze Laufzeit. Dienste prüfen sie meist ohnehin nur beim Login.
 */
@Service
public class TokenRevocationService {

    private final BackchannelLogoutService logout;

    public TokenRevocationService(BackchannelLogoutService logout) {
        this.logout = logout;
    }

    /**
     * Löscht alle Autorisierungen des Users und benachrichtigt die Dienste (Logout-Token), damit auch ihre Sitzungen enden.
     * @return Anzahl der gelöschten Autorisierungen
     */
    public int revokeAll(UUID userId) {
        return logout.endAllSessions(userId);
    }
}
