package net.anfall.auth.admincli;

import java.io.PrintStream;
import java.util.List;
import net.anfall.auth.oidc.SigningKeyService;
import org.springframework.stereotype.Component;

/** Macht einen neuen Signierschlüssel zum aktiven. Der alte bleibt noch zwei Tage im JWKS (Prüfen alter ID-Tokens). */
@Component
class RotateSigningKeyCommand implements AdminCommand {

    private final SigningKeyService keys;

    RotateSigningKeyCommand(SigningKeyService keys) {
        this.keys = keys;
    }

    @Override
    public String name() {
        return "rotate-signing-key";
    }

    @Override
    public String usage() {
        return "rotate-signing-key   erzeugt einen neuen Signierschlüssel, der alte bleibt zwei Tage zum Prüfen";
    }

    @Override
    public int run(List<String> args, PrintStream out) {
        out.println("Neuer Signierschlüssel aktiv: " + keys.rotate());
        return 0;
    }
}
