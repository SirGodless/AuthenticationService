package net.anfall.auth.invite;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import net.anfall.auth.testsupport.Browser;
import net.anfall.auth.testsupport.InviteTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Sperre pro IP bei falschen Links (nur Links, die es nie gab) und ihre Aufhebung durch einen Admin. */
class InviteIpBlockTest extends InviteTestBase {

    private static final String BAD_IP = "203.0.113.7";

    private int open(String ip, String token) throws Exception {
        return browser().from(ip).get("/api/invite/" + token).status();
    }

    @Test
    void theFourthWrongLinkWithinAnHourBlocksTheAddressForADay() throws Exception {
        for (int i = 1; i <= 3; i++) {
            assertThat(open(BAD_IP, "falsch" + i)).isEqualTo(404);
        }
        assertThat(open(BAD_IP, "falsch4")).isEqualTo(404);     // der vierte zählt noch als falsch, danach ist gesperrt
        Browser.Result blocked = browser().from(BAD_IP).get("/api/invite/falsch5");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body()).contains("ip_blocked");
        // auch ein gültiger Link geht von dieser Adresse nicht mehr
        String valid = createLink(creator("anna", 3));
        assertThat(open(BAD_IP, valid)).isEqualTo(429);
        assertThat(register(browser().from(BAD_IP), valid, "bob", "bob@example.com", NEW_PASSWORD).status()).isEqualTo(429);
        assertThat(jdbc.sql("SELECT blocked_until > now() + interval '23 hours' FROM invite_ip_blocks").query(Boolean.class).single()).isTrue();
    }

    @Test
    void threeWrongLinksDoNotBlock() throws Exception {
        for (int i = 1; i <= 3; i++) {
            open(BAD_IP, "falsch" + i);
        }
        String valid = createLink(creator("anna", 3));
        assertThat(open(BAD_IP, valid)).isEqualTo(200);
    }

    @Test
    void theBlockOnlyAffectsTheLinkEndpointAndThatAddress() throws Exception {
        creator("anna", 3);
        for (int i = 1; i <= 4; i++) {
            open(BAD_IP, "falsch" + i);
        }
        assertThat(open(BAD_IP, "irgendwas")).isEqualTo(429);
        // Login von derselben Adresse geht weiter
        assertThat(login(browser().from(BAD_IP), "anna", PASSWORD).ok()).isTrue();
        // andere Adresse ist frei
        assertThat(open("203.0.113.8", "irgendwas")).isEqualTo(404);
    }

    @Test
    void expiredUsedAndDeactivatedLinksDoNotCount() throws Exception {
        Session anna = creator("anna", 20);
        String used = createLink(anna);
        register(used, "bob", "bob@example.com");
        Browser.Result expired = tryCreate(anna, body(null, null, null));
        jdbc.sql("UPDATE invite_links SET expires_at = now() - interval '1 minute' WHERE id = :i").param("i", linkIdOf(expired)).update();
        Browser.Result deactivated = tryCreate(anna, body(null, null, null));
        anna.browser().post("/api/invites/" + linkIdOf(deactivated) + "/deactivate", Map.of());
        String[] tokens = {used, tokenOfExpired(expired), tokenOfDeactivated(deactivated)};
        for (int round = 0; round < 3; round++) {
            for (String t : tokens) {
                assertThat(open(BAD_IP, t)).isEqualTo(404);
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM invite_ip_blocks").query(Long.class).single()).isZero();
        assertThat(open(BAD_IP, createLink(anna))).isEqualTo(200);
    }

    private String tokenOfExpired(Browser.Result r) throws Exception {
        return tokenOf(r);
    }

    private String tokenOfDeactivated(Browser.Result r) throws Exception {
        return tokenOf(r);
    }

    @Test
    void ipv6AddressesAreCountedPerSlash64() throws Exception {
        for (int i = 1; i <= 4; i++) {
            open("2001:db8:1:2::" + i, "falsch" + i);
        }
        assertThat(open("2001:db8:1:2:ffff::9", "x")).isEqualTo(429);
        assertThat(open("2001:db8:1:3::1", "x")).isEqualTo(404);
    }

    @Test
    void wrongLinksInASubmitCountAsWell() throws Exception {
        for (int i = 1; i <= 4; i++) {
            register(browser().from(BAD_IP), "falsch" + i, "bob", "bob@example.com", NEW_PASSWORD);
        }
        assertThat(open(BAD_IP, "x")).isEqualTo(429);
    }

    @Test
    void anAdminCanSeeAndLiftTheBlock() throws Exception {
        Session admin = loggedInAdmin("joshua");
        giveSettingsRight(admin);
        for (int i = 1; i <= 4; i++) {
            open(BAD_IP, "falsch" + i);
        }
        JsonNode blocks = json.readTree(admin.browser().get("/api/admin/settings/invites").body()).get("blocks");
        assertThat(blocks).hasSize(1);
        String key = blocks.get(0).get("ipKey").asString();
        assertThat(open(BAD_IP, "x")).isEqualTo(429);

        assertThat(admin.browser().delete("/api/admin/settings/invites/blocks?ipKey=" + key).ok()).isTrue();
        assertThat(open(BAD_IP, "x")).isEqualTo(404);
        assertThat(admin.browser().delete("/api/admin/settings/invites/blocks?ipKey=unbekannt").status()).isEqualTo(404);
    }

    private void giveSettingsRight(Session admin) {
        // die Rolle Admin enthält admin.settings.manage schon, nichts zu tun
    }

    @Test
    void liftingNeedsTheSettingsRight() throws Exception {
        Session viewer = loggedInWithPermissions("anna", true, "admin.users.view");
        assertThat(viewer.browser().delete("/api/admin/settings/invites/blocks?ipKey=x").status()).isEqualTo(403);
    }
}
