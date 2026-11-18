package net.anfall.auth.invite;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.InviteTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Links erzeugen: Recht, Platzlimit, Gültigkeit, was vergeben werden darf, deaktivieren. */
class InviteLinkCreationTest extends InviteTestBase {

    private Instant expiresOf(UUID linkId) {
        return jdbc.sql("SELECT expires_at FROM invite_links WHERE id = :i").param("i", linkId).query(java.sql.Timestamp.class).single().toInstant();
    }

    @Test
    void creatingNeedsTheRight() throws Exception {
        Session s = loggedInWithPermissions("anna", false);
        assertThat(tryCreate(s, body(null, null, null)).status()).isEqualTo(403);
        assertThat(browser().post("/api/invites", Map.of()).status()).isEqualTo(401);
    }

    @Test
    void noLimitSetMeansNoSpace() throws Exception {
        Session s = loggedInWithPermissions("anna", false, "links.create");
        Browser.Result r = tryCreate(s, body(null, null, null));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("link_limit_reached");
    }

    @Test
    void theTokenIsLongAndOnlyItsHashIsStored() throws Exception {
        Session s = creator("anna", 3);
        Browser.Result r = tryCreate(s, body(null, null, null));
        String token = tokenOf(r);
        assertThat(token).hasSize(43);
        String stored = jdbc.sql("SELECT token_hash FROM invite_links").query(String.class).single();
        assertThat(stored).isNotEqualTo(token).hasSize(64);
        assertThat(jdbc.sql("SELECT action || details::text FROM audit_log WHERE action = 'invite.created'").query(String.class).single())
                .doesNotContain(token);
    }

    @Test
    void defaultValidityIsSevenDaysAndShortestIsOneHour() throws Exception {
        Session s = creator("anna", 5);
        Browser.Result def = tryCreate(s, body(null, null, null));
        assertThat(Duration.between(Instant.now(), expiresOf(linkIdOf(def)))).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
        Browser.Result hour = tryCreate(s, body(1, null, null));
        assertThat(Duration.between(Instant.now(), expiresOf(linkIdOf(hour)))).isBetween(Duration.ofMinutes(59), Duration.ofHours(1));
        assertThat(tryCreate(s, body(0, null, null)).body()).contains("validity_too_short");
    }

    @Test
    void longestIsThirtyDays() throws Exception {
        Session s = creator("anna", 5);
        assertThat(tryCreate(s, body(30 * 24, null, null)).ok()).isTrue();
        Browser.Result r = tryCreate(s, body(30 * 24 + 1, null, null));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("validity_too_long");
    }

    @Test
    void linksWithRightsAreShorterAndDefaultToThreeDays() throws Exception {
        Session s = creator("anna", 5);
        jdbc.sql("INSERT INTO permissions (key, type, builtin, link_grantable) VALUES ('service.vault', 'boolean', false, true)").update();
        givePermission(s.id(), "service.vault");
        Browser.Result def = tryCreate(s, body(null, null, List.of(grant("service.vault"))));
        assertThat(def.ok()).as(def.body()).isTrue();
        assertThat(Duration.between(Instant.now(), expiresOf(linkIdOf(def)))).isBetween(Duration.ofDays(3).minusMinutes(1), Duration.ofDays(3));
        Browser.Result tooLong = tryCreate(s, body(3 * 24 + 1, null, List.of(grant("service.vault"))));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("validity_too_long_privileged");
        assertThat(tryCreate(s, body(3 * 24, null, List.of(grant("service.vault")))).ok()).isTrue();
    }

    @Test
    void theActiveLimitCountsOpenLinksOnlyAndFreesSpaceWhenDeactivatedOrExpired() throws Exception {
        Session s = creator("anna", 2);
        Browser.Result first = tryCreate(s, body(null, null, null));
        Browser.Result second = tryCreate(s, body(null, null, null));
        assertThat(first.ok() && second.ok()).isTrue();
        assertThat(tryCreate(s, body(null, null, null)).status()).isEqualTo(409);

        assertThat(s.browser().post("/api/invites/" + linkIdOf(first) + "/deactivate", Map.of()).ok()).isTrue();
        Browser.Result third = tryCreate(s, body(null, null, null));
        assertThat(third.ok()).isTrue();
        assertThat(tryCreate(s, body(null, null, null)).status()).isEqualTo(409);

        jdbc.sql("UPDATE invite_links SET expires_at = now() - interval '1 minute' WHERE id = :i").param("i", linkIdOf(second)).update();
        assertThat(tryCreate(s, body(null, null, null)).ok()).isTrue();
    }

    @Test
    void aUsedLinkFreesItsSpace() throws Exception {
        Session s = creator("anna", 1);
        String token = createLink(s);
        assertThat(tryCreate(s, body(null, null, null)).status()).isEqualTo(409);
        assertThat(register(token, "bob", "bob@example.com").ok()).isTrue();
        assertThat(tryCreate(s, body(null, null, null)).ok()).isTrue();
    }

    @Test
    void unlimitedRemovesTheLimit() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        givePermission(id, "links.create");
        jdbc.sql("INSERT INTO user_permissions (user_id, permission_key, unlimited) VALUES (:u, 'links.max_active', true)").param("u", id).update();
        Browser b = browser();
        login(b, "anna", PASSWORD);
        Session s = new Session(id, b, null);
        for (int i = 0; i < 6; i++) {
            assertThat(tryCreate(s, body(null, null, null)).ok()).isTrue();
        }
    }

    @Test
    void twoSimultaneousRequestsCannotBothTakeTheLastSpace() throws Exception {
        UUID id = createUser("anna", "anna@example.com", PASSWORD, true);
        givePermission(id, "links.create");
        giveNumber(id, "links.max_active", 1);
        Browser b1 = browser();
        Browser b2 = browser();
        login(b1, "anna", PASSWORD);
        login(b2, "anna", PASSWORD);
        b1.open();
        b2.open();
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(() -> b1.post("/api/invites", body(null, null, null)).status(),
                    () -> b2.post("/api/invites", body(null, null, null)).status());
            List<Integer> codes = pool.invokeAll(jobs).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Was ein Link vergeben darf ----

    @Test
    void creatorCanOnlyGrantRightsTheyHoldThatAreLinkGrantable() throws Exception {
        Session s = creator("anna", 9);
        jdbc.sql("INSERT INTO permissions (key, type, builtin, link_grantable) VALUES ('service.vault', 'boolean', false, true), ('service.abs', 'boolean', false, true), ('service.closed', 'boolean', false, false)").update();
        givePermission(s.id(), "service.vault");
        givePermission(s.id(), "service.closed");
        assertThat(tryCreate(s, body(null, null, List.of(grant("service.vault")))).ok()).isTrue();
        Browser.Result notHeld = tryCreate(s, body(null, null, List.of(grant("service.abs"))));
        assertThat(notHeld.status()).isEqualTo(403);
        assertThat(notHeld.body()).contains("grant_not_held");
        Browser.Result notGrantable = tryCreate(s, body(null, null, List.of(grant("service.closed"))));
        assertThat(notGrantable.body()).contains("permission_not_link_grantable");
        assertThat(tryCreate(s, body(null, null, List.of(grant("service.nothing")))).status()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void adminRightsAreNeverGrantedByLink() throws Exception {
        Session admin = loggedInAdmin("joshua");
        giveNumber(admin.id(), "links.max_active", 5);
        givePermission(admin.id(), "links.create");
        setLinkGrantable("admin.users.view", true);
        Browser.Result r = tryCreate(admin, body(null, null, List.of(grant("admin.users.view"))));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).contains("permission_not_link_grantable");
        // Auch die Rolle Admin nicht
        assertThat(tryCreate(admin, body(null, adminRoleId(), null)).body()).contains("permission_not_link_grantable");
    }

    @Test
    void numberRightsCannotExceedTheCreatorsOwnValue() throws Exception {
        Session s = creator("anna", 9);
        setLinkGrantable("links.max_active", true);
        assertThat(tryCreate(s, body(null, null, List.of(grant("links.max_active", 9)))).ok()).isTrue();
        Browser.Result tooMuch = tryCreate(s, body(null, null, List.of(grant("links.max_active", 10))));
        assertThat(tooMuch.status()).isEqualTo(403);
        Map<String, Object> unlimited = grant("links.max_active");
        unlimited.put("unlimited", true);
        assertThat(tryCreate(s, body(null, null, List.of(unlimited))).status()).isEqualTo(403);
    }

    @Test
    void aRoleNeedsAllItsRightsHeldAndLinkGrantable() throws Exception {
        Session s = creator("anna", 9);
        jdbc.sql("INSERT INTO permissions (key, type, builtin, link_grantable) VALUES ('service.vault', 'boolean', false, true), ('service.abs', 'boolean', false, true)").update();
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Guests')").param("i", role).update();
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'service.vault'), (:r, 'service.abs')").param("r", role).update();
        givePermission(s.id(), "service.vault");
        assertThat(tryCreate(s, body(null, role, null)).status()).isEqualTo(403); // service.abs fehlt
        givePermission(s.id(), "service.abs");
        Browser.Result ok = tryCreate(s, body(null, role, null));
        assertThat(ok.ok()).as(ok.body()).isTrue();
        // zählt als Link mit Rechten: höchstens 3 Tage
        assertThat(Duration.between(Instant.now(), expiresOf(linkIdOf(ok)))).isLessThanOrEqualTo(Duration.ofDays(3));
        assertThat(tryCreate(s, body(10 * 24, role, null)).status()).isEqualTo(400);
        // nicht link-vergebbar
        setLinkGrantable("service.abs", false);
        assertThat(tryCreate(s, body(null, role, null)).body()).contains("permission_not_link_grantable");
    }

    @Test
    void theLinkStoresTheResolvedRightsAtCreation() throws Exception {
        Session s = creator("anna", 9);
        jdbc.sql("INSERT INTO permissions (key, type, builtin, link_grantable) VALUES ('service.vault', 'boolean', false, true)").update();
        givePermission(s.id(), "service.vault");
        UUID role = UUID.randomUUID();
        jdbc.sql("INSERT INTO roles (id, name) VALUES (:i, 'Guests')").param("i", role).update();
        jdbc.sql("INSERT INTO role_permissions (role_id, permission_key) VALUES (:r, 'service.vault')").param("r", role).update();
        Browser.Result r = tryCreate(s, body(null, role, null));
        var row = jdbc.sql("SELECT role_name, role_permissions::text AS rp FROM invite_links WHERE id = :i").param("i", linkIdOf(r)).query().singleRow();
        assertThat(row.get("role_name")).isEqualTo("Guests");
        assertThat(String.valueOf(row.get("rp"))).contains("service.vault");
    }

    // ---- Liste, Deaktivieren, Optionen ----

    @Test
    void ownLinksAreListedWithStatusAndOthersAreNotVisibleOrChangeable() throws Exception {
        Session anna = creator("anna", 5);
        Session bob = creator("bob", 5);
        Browser.Result r = tryCreate(anna, body(null, null, null));
        JsonNode list = json.readTree(anna.browser().get("/api/invites").body());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("status").asString()).isEqualTo("active");
        assertThat(json.readTree(bob.browser().get("/api/invites").body())).isEmpty();
        assertThat(bob.browser().post("/api/invites/" + linkIdOf(r) + "/deactivate", Map.of()).status()).isEqualTo(404);
        assertThat(anna.browser().post("/api/invites/" + linkIdOf(r) + "/deactivate", Map.of()).ok()).isTrue();
        assertThat(anna.browser().post("/api/invites/" + linkIdOf(r) + "/deactivate", Map.of()).status()).isEqualTo(409);
        assertThat(json.readTree(anna.browser().get("/api/invites").body()).get(0).get("status").asString()).isEqualTo("deactivated");
    }

    @Test
    void linksAreNeverDeletedAndTheListShowsExpiredAndUsed() throws Exception {
        Session anna = creator("anna", 5);
        Browser.Result expired = tryCreate(anna, body(null, null, null));
        jdbc.sql("UPDATE invite_links SET expires_at = now() - interval '1 hour' WHERE id = :i").param("i", linkIdOf(expired)).update();
        String token = createLink(anna);
        register(token, "bob", "bob@example.com");
        JsonNode list = json.readTree(anna.browser().get("/api/invites").body());
        assertThat(list.valueStream().map(n -> n.get("status").asString())).containsExactlyInAnyOrder("expired", "used");
        assertThat(jdbc.sql("SELECT count(*) FROM invite_links").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void optionsTellWhatTheUserMayDo() throws Exception {
        Session anna = creator("anna", 3);
        tryCreate(anna, body(null, null, null));
        JsonNode o = json.readTree(anna.browser().get("/api/invites/options").body());
        assertThat(o.get("canCreate").asBoolean()).isTrue();
        assertThat(o.get("activeCount").asLong()).isEqualTo(1);
        assertThat(o.get("limits").get("defaultDays").asInt()).isEqualTo(7);
        Session none = loggedInWithPermissions("none", false);
        assertThat(json.readTree(none.browser().get("/api/invites/options").body()).get("canCreate").asBoolean()).isFalse();
    }
}
