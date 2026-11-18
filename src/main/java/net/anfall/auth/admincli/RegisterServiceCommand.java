package net.anfall.auth.admincli;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import net.anfall.auth.service.ServiceRegistrationService;
import net.anfall.auth.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * Legt einen Dienst an (Recht, OIDC-Client, Hub-Eintrag) und zeigt das Client-Geheimnis einmal an.
 * Gedacht für den Anfang, bis das Admin-Dashboard (Etappe 4) die Verwaltung übernimmt.
 */
@Component
class RegisterServiceCommand implements AdminCommand {

    private final ServiceRegistrationService registration;

    RegisterServiceCommand(ServiceRegistrationService registration) {
        this.registration = registration;
    }

    @Override
    public String name() {
        return "register-service";
    }

    @Override
    public String usage() {
        return "register-service <key> <Anzeigename> <redirect-uri>[,<redirect-uri>...]   legt einen Dienst an";
    }

    @Override
    public int run(List<String> args, PrintStream out) {
        if (args.size() != 3) {
            System.err.println("Aufruf: " + usage());
            return 2;
        }
        try {
            var registered = registration.register(args.get(0), args.get(1), Arrays.asList(args.get(2).split(",")));
            out.println("Dienst angelegt.");
            out.println("  Client-ID:      " + registered.service().clientId());
            out.println("  Client-Secret:  " + registered.clientSecret() + "   (wird nur jetzt angezeigt)");
            out.println("  Recht:          " + registered.service().requiredPermission()
                    + "   (vergib es mit: grant-service <username> " + args.get(0) + ")");
            return 0;
        } catch (ApiException e) {
            System.err.println("Fehler: " + e.code());
            return 1;
        }
    }
}
