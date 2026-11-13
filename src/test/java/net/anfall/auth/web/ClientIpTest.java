package net.anfall.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import net.anfall.auth.config.AppProperties;
import net.anfall.auth.crypto.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpTest {

    private final ClientIp clientIp = new ClientIp(new AppProperties(
            "https://auth.example.test", "k", "i", null, null, "f", "no-reply@example.test",
            new AppProperties.Session(null, null, null), new AppProperties.Tokens(null, null),
            new AppProperties.Limits(5, null, null, 3, null),
            List.of("172.16.0.0/12", "127.0.0.0/8", "fc00::/7")));

    private MockHttpServletRequest request(String remote, String forwarded) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (forwarded != null) {
            r.addHeader("X-Forwarded-For", forwarded);
        }
        return r;
    }

    @Test
    void trustedProxyHeaderIsUsed() {
        assertThat(clientIp.of(request("172.18.0.4", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void lastEntryCountsBecauseCaddyAppendsTheRealAddress() {
        // Ein Besucher schickt selbst "1.1.1.1", Caddy hängt die echte Adresse hinten an
        assertThat(clientIp.of(request("172.18.0.4", "1.1.1.1, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void headerFromUntrustedSenderIsIgnored_soTheLockCannotBeSpoofed() {
        assertThat(clientIp.of(request("198.51.100.20", "203.0.113.7"))).isEqualTo("198.51.100.20");
    }

    @Test
    void withoutHeaderTheDirectAddressIsUsed() {
        assertThat(clientIp.of(request("172.18.0.4", null))).isEqualTo("172.18.0.4");
        assertThat(clientIp.of(request("172.18.0.4", "  "))).isEqualTo("172.18.0.4");
    }

    @Test
    void garbageInTheHeaderFallsBackToTheDirectAddress() {
        assertThat(clientIp.of(request("172.18.0.4", "not-an-ip"))).isEqualTo("172.18.0.4");
        assertThat(clientIp.of(request("172.18.0.4", "beef"))).isEqualTo("172.18.0.4"); // nur Hex-Zeichen, kein Hostname-Lookup
        assertThat(clientIp.of(request("172.18.0.4", "999.1.1"))).isEqualTo("172.18.0.4");
    }

    @Test
    void ipv6IsReducedToItsSlash64() {
        assertThat(ClientIp.rateKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("20010db800010002/64");
        assertThat(ClientIp.rateKey("2001:db8:1:2::1")).isEqualTo(ClientIp.rateKey("2001:db8:1:2:ffff::9"));
        assertThat(ClientIp.rateKey("2001:db8:1:3::1")).isNotEqualTo(ClientIp.rateKey("2001:db8:1:2::1"));
        assertThat(ClientIp.rateKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }
}
