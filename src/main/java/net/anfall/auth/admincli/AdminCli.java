package net.anfall.auth.admincli;

import java.util.Comparator;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startet einen Betreiber-Befehl und beendet das Programm danach. Läuft ohne Webserver.
 * Aufruf (siehe README): docker compose exec auth-backend auth-admin &lt;befehl&gt; &lt;parameter&gt;
 * Jeder Befehl ist eine eigene Klasse im selben Paket (Name endet auf Command).
 */
@Component
@Order(2)
@ConditionalOnExpression("${app.cli.enabled:false}")
class AdminCli implements ApplicationRunner {

    private final ConfigurableApplicationContext context;
    private final List<AdminCommand> commands;

    AdminCli(ConfigurableApplicationContext context, List<AdminCommand> commands) {
        this.context = context;
        this.commands = commands.stream().sorted(Comparator.comparing(AdminCommand::name)).toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        int code;
        try {
            code = execute(args.getNonOptionArgs());
        } catch (RuntimeException e) {
            System.err.println("Fehler: " + e.getMessage());
            code = 1;
        }
        final int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    /** Sucht den Befehl und führt ihn aus. Getrennt von run(), damit man es testen kann, ohne das Programm zu beenden. */
    int execute(List<String> args) {
        if (args.isEmpty()) {
            return usage("Kein Befehl angegeben.");
        }
        return commands.stream().filter(c -> c.name().equals(args.get(0))).findFirst()
                .map(c -> c.run(args.subList(1, args.size()), System.out))
                .orElseGet(() -> usage("Unbekannter Befehl '" + args.get(0) + "'."));
    }

    private int usage(String problem) {
        System.err.println(problem + " Verfügbare Befehle:");
        commands.forEach(c -> System.err.println("  " + c.usage()));
        return 2;
    }
}
