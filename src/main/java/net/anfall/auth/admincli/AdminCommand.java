package net.anfall.auth.admincli;

import java.io.PrintStream;
import java.util.List;

/** Ein Befehl für den Betreiber im Backend-Container (docker compose exec auth-backend auth-admin ...). */
interface AdminCommand {

    /** Name auf der Kommandozeile, z. B. "reset-2fa". */
    String name();

    /** Eine Zeile Aufruf mit Parametern für die Hilfe. */
    String usage();

    /** @return 0 bei Erfolg, sonst ein Fehlercode. Ausgaben gehen nach out, Fehler nach System.err. */
    int run(List<String> args, PrintStream out);
}
