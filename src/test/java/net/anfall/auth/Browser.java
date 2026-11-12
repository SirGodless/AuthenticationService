package net.anfall.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simuliert einen Browser: merkt sich Cookies (Sitzung, CSRF) und schickt den CSRF-Token als Header zurück,
 * so wie es die UI tut. Dadurch laufen die Tests durch denselben Schutz wie echte Anfragen.
 */
public class Browser {

    /** Antwort mit Status, Body und den rohen Set-Cookie-Zeilen. */
    public record Result(int status, String body, List<String> setCookies) {
        public boolean ok() {
            return status == 200;
        }

        public boolean bodyContains(String text) {
            return body.contains(text);
        }
    }

    private final MockMvc mvc;
    private final JsonMapper json;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    /** Absender-IP, wie sie hinter dem Proxy ankäme (X-Forwarded-For). */
    private String forwardedFor;

    Browser(MockMvc mvc, JsonMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public Browser from(String ip) {
        this.forwardedFor = ip;
        return this;
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public Result get(String path) throws Exception {
        return send(MockMvcRequestBuilders.get(path), null);
    }

    /** Wie ein Seitenaufruf, damit das CSRF-Cookie gesetzt wird (die UI ruft /api/auth/me beim Laden). */
    public Browser open() throws Exception {
        get("/api/auth/me");
        return this;
    }

    public Result post(String path, Map<String, ?> body) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            open();
        }
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        return send(req, body);
    }

    /** POST ohne CSRF-Header (so würde ein fremder Tab senden). */
    public Result postWithoutCsrf(String path, Map<String, ?> body) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        return send(req, body);
    }

    private Result send(MockHttpServletRequestBuilder req, Object ignored) throws Exception {
        // MockMvc wertet einen Cookie-Header nicht aus, Cookies müssen als Objekte mitgegeben werden
        if (!cookies.isEmpty()) {
            req.cookie(cookies.entrySet().stream()
                    .map(e -> new jakarta.servlet.http.Cookie(e.getKey(), e.getValue()))
                    .toArray(jakarta.servlet.http.Cookie[]::new));
        }
        if (forwardedFor != null) {
            req.header("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        List<String> setCookies = res.getHeaders("Set-Cookie");
        for (String line : setCookies) {
            String[] first = line.split(";", 2)[0].split("=", 2);
            boolean deleted = line.toLowerCase().contains("max-age=0");
            if (deleted) {
                cookies.remove(first[0]);
            } else {
                cookies.put(first[0], first.length > 1 ? first[1] : "");
            }
        }
        return new Result(res.getStatus(), res.getContentAsString(), setCookies);
    }
}
