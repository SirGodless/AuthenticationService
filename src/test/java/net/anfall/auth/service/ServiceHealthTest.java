package net.anfall.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import net.anfall.auth.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Statusprüfung der Dienste: nur eine Anzeige, Ergebnis im Speicher. */
class ServiceHealthTest extends IntegrationTestBase {

    @Autowired ServiceRegistrationService registration;
    @Autowired ServiceHealthService health;

    private HttpServer server(int status) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/alive", ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        s.start();
        return s;
    }

    private void setHealth(String key, String url) {
        jdbc.sql("UPDATE services SET health_url = :u WHERE key = :k").param("u", url).param("k", key).update();
    }

    @Test
    void anAnsweringServiceIsUpAndAFailingOneIsDown() throws Exception {
        registration.register("vault", "Vaultwarden", List.of("https://v.example/cb"));
        registration.register("abs", "Audiobookshelf", List.of("https://a.example/cb"));
        registration.register("pel", "Pelican", List.of("https://p.example/cb"));
        HttpServer ok = server(200);
        HttpServer broken = server(500);
        try {
            setHealth("vault", "http://127.0.0.1:" + ok.getAddress().getPort() + "/alive");
            setHealth("abs", "http://127.0.0.1:" + broken.getAddress().getPort() + "/alive");
            setHealth("pel", "http://127.0.0.1:1/alive");   // niemand lauscht
            health.checkNow();
            assertThat(health.status("vault").state()).isEqualTo("up");
            assertThat(health.status("vault").checkedAt()).isNotNull();
            assertThat(health.status("abs").state()).isEqualTo("down");
            assertThat(health.status("pel").state()).isEqualTo("down");
        } finally {
            ok.stop(0);
            broken.stop(0);
        }
    }

    @Test
    void redirectsCountAsUpButAreNotFollowed() throws Exception {
        registration.register("vault", "Vaultwarden", List.of("https://v.example/cb"));
        HttpServer redirect = server(302);
        try {
            setHealth("vault", "http://127.0.0.1:" + redirect.getAddress().getPort() + "/alive");
            health.checkNow();
            assertThat(health.status("vault").state()).isEqualTo("up");
        } finally {
            redirect.stop(0);
        }
    }

    @Test
    void withoutHealthUrlTheStatusIsUnknown() {
        registration.register("vault", "Vaultwarden", List.of("https://v.example/cb"));
        health.checkNow();
        assertThat(health.status("vault").state()).isEqualTo("unknown");
        assertThat(health.status("gibt-es-nicht").state()).isEqualTo("unknown");
    }

    @Test
    void theStatusFollowsTheServiceWhenItComesBack() throws Exception {
        registration.register("vault", "Vaultwarden", List.of("https://v.example/cb"));
        HttpServer s = server(200);
        setHealth("vault", "http://127.0.0.1:" + s.getAddress().getPort() + "/alive");
        health.checkNow();
        assertThat(health.status("vault").state()).isEqualTo("up");
        s.stop(0);
        health.checkNow();
        assertThat(health.status("vault").state()).isEqualTo("down");
    }

    @Test
    void theMailServerIsChecked() {
        health.checkNow();
        assertThat(health.mailStatus().state()).isEqualTo("up");   // GreenMail im Test
    }
}
