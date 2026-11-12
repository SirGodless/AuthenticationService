package net.anfall.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;
import net.anfall.auth.config.AppProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Ermittelt die echte IP des Besuchers. X-Forwarded-For gilt nur, wenn die Anfrage von einem
 * vertrauenswürdigen Proxy (Caddy) kommt. Caddy setzt den Header auf die echte Client-IP,
 * deshalb zählt der letzte Eintrag. Von anderen Absendern wird der Header ignoriert.
 */
@Component
public class ClientIp {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final List<IpAddressMatcher> trusted;

    public ClientIp(AppProperties props) {
        this.trusted = props.trustedProxies().stream().map(IpAddressMatcher::new).toList();
    }

    public String of(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] parts = forwarded.split(",");
        String last = parts[parts.length - 1].trim();
        return isIpLiteral(last) ? last : remote;
    }

    private boolean isTrusted(String address) {
        try {
            return trusted.stream().anyMatch(m -> m.matches(address));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Nur echte IP-Zeichenfolgen zulassen. Verhindert auch, dass ein Hostname aufgelöst wird. */
    private static boolean isIpLiteral(String s) {
        return IPV4.matcher(s).matches() || (s.contains(":") && s.matches("[0-9a-fA-F:.]+"));
    }

    /**
     * Schlüssel für Zähler und Sperren: IPv4 unverändert, IPv6 auf das /64-Netz gekürzt
     * (ein einzelner Anschluss hat meist ein ganzes /64 und kann sonst beliebig wechseln).
     */
    public static String rateKey(String ip) {
        if (ip == null || !ip.contains(":")) {
            return String.valueOf(ip);
        }
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) {
                return ip;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", b[i]));
            }
            return sb + "/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
