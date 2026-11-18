package net.anfall.auth.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hilfen für die Tests der Einladungslinks: Ersteller mit Rechten, Links über die API erzeugen und einlösen. */
public abstract class InviteTestBase extends AdminTestBase {

    protected static final String NEW_PASSWORD = "an entirely different passphrase 77";

    protected void givePermission(UUID user, String key) {
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key) VALUES (:u, :k)").param("u", user).param("k", key).update();
    }

    protected void giveNumber(UUID user, String key, long number) {
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key, number_value) VALUES (:u, :k, :n)")
                .param("u", user).param("k", key).param("n", number).update();
    }

    protected void setLinkGrantable(String key, boolean value) {
        jdbc.sql("UPDATE permissions SET link_grantable = :v WHERE key = :k").param("v", value).param("k", key).update();
    }

    /** Eingeloggter User mit links.create und einem Limit gleichzeitig aktiver Links. */
    protected Session creator(String name, long maxActive) throws Exception {
        UUID id = createUser(name, name + "@example.com", PASSWORD, true);
        givePermission(id, "links.create");
        giveNumber(id, "links.max_active", maxActive);
        Browser b = browser();
        assertThat(login(b, name, PASSWORD).ok()).isTrue();
        return new Session(id, b, null);
    }

    protected static Map<String, Object> body(Integer validityHours, UUID roleId, List<Map<String, Object>> permissions) {
        Map<String, Object> m = new HashMap<>();
        if (validityHours != null) m.put("validityHours", validityHours);
        if (roleId != null) m.put("roleId", roleId.toString());
        if (permissions != null) m.put("permissions", permissions);
        return m;
    }

    protected static Map<String, Object> grant(String key) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("unlimited", false);
        return m;
    }

    protected static Map<String, Object> grant(String key, long number) {
        Map<String, Object> m = grant(key);
        m.put("number", number);
        return m;
    }

    protected Browser.Result tryCreate(Session s, Map<String, Object> body) throws Exception {
        return s.browser().post("/api/invites", body);
    }

    protected String tokenOf(Browser.Result created) throws Exception {
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        String url = json.readTree(created.body()).get("url").asString();
        assertThat(url).startsWith("https://auth.example.test/invite/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    protected String createLink(Session s) throws Exception {
        return tokenOf(tryCreate(s, body(null, null, null)));
    }

    protected UUID linkIdOf(Browser.Result created) throws Exception {
        return UUID.fromString(json.readTree(created.body()).get("id").asString());
    }

    protected Browser.Result register(String token, String username, String email) throws Exception {
        return register(browser(), token, username, email, NEW_PASSWORD);
    }

    protected Browser.Result register(Browser b, String token, String username, String email, String password) throws Exception {
        return b.post("/api/invite/" + token + "/register",
                Map.of("username", username, "email", email, "password", password, "language", "en"));
    }

    protected UUID idOf(String username) {
        return jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", username).query(UUID.class).single();
    }

    protected String linkStatus(UUID linkId) {
        return jdbc.sql("SELECT status FROM invite_links WHERE id = :i").param("i", linkId).query(String.class).single();
    }
}
