package net.anfall.auth.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Ein Webserver wie ein Next.js-Dienst: Eine Anfrage mit "Upgrade:"-Kopf (z. B. h2c vom Java-HttpClient) bekommt keine Antwort,
 * die Verbindung wird geschlossen. Alle anderen bekommen den eingestellten Status. Gespeichert werden Anfragezeile und Körper.
 */
public class UpgradeAverseServer implements AutoCloseable {

    private final ServerSocket socket;
    private final int status;
    public final List<String> requests = new CopyOnWriteArrayList<>();

    public UpgradeAverseServer(int status) throws IOException {
        this.status = status;
        this.socket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(this::serve, "upgrade-averse-server");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket c = socket.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
                String first = in.readLine();
                boolean upgrade = false;
                int length = 0;
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    String l = line.toLowerCase();
                    upgrade |= l.startsWith("upgrade:");
                    if (l.startsWith("content-length:")) {
                        length = Integer.parseInt(l.substring(15).trim());
                    }
                }
                if (upgrade) {
                    continue;   // Verbindung ohne Antwort schließen
                }
                char[] body = new char[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                requests.add(first + " " + new String(body, 0, read));
                c.getOutputStream().write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // Test beendet
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
