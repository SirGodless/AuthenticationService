package net.anfall.auth.service;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL für die Tabelle services. */
@Component
public class ServiceRepository {

    private static final String COLUMNS = "key, display_name, client_id, required_permission, open_mode, health_url, description, url, accent_color, reported_accent_color, accent_locked, backchannel_logout_uri";

    private final JdbcClient jdbc;

    public ServiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ServiceInfo> findByClientId(String clientId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM services WHERE client_id = :c").param("c", clientId)
                .query(ServiceRepository::map).optional();
    }

    public Optional<ServiceInfo> findByKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM services WHERE key = :k").param("k", key)
                .query(ServiceRepository::map).optional();
    }

    public List<ServiceInfo> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM services ORDER BY display_name").query(ServiceRepository::map)
                .list();
    }

    public void insert(ServiceInfo s) {
        jdbc.sql("""
                        INSERT INTO services (key, display_name, client_id, required_permission, open_mode, health_url, description, url, accent_color,
                                              reported_accent_color, accent_locked, backchannel_logout_uri)
                        VALUES (:k, :n, :c, :p, :m, :h, :d, :u, :a, :r, :l, :b)
                        """)
                .param("k", s.key()).param("n", s.displayName()).param("c", s.clientId())
                .param("p", s.requiredPermission()).param("m", s.openMode()).param("h", s.healthUrl())
                .param("d", s.description()).param("u", s.url()).param("a", s.accentColor())
                .param("r", s.reportedAccentColor()).param("l", s.accentLocked())
                .param("b", s.backchannelLogoutUri()).update();
    }

    /** Ändert die Anzeige-Eigenschaften eines Dienstes (Name, Beschreibung, Adresse, Farbe, Health-Adresse, Öffnungsmodus, Logout-Adresse). */
    public void updateDisplay(String key, String displayName, String description, String url, String accentColor,
                              boolean accentLocked, String healthUrl, String openMode, String backchannelLogoutUri) {
        jdbc.sql("""
                        UPDATE services SET display_name = :n, description = :d, url = :u, accent_color = :c, accent_locked = :l, health_url = :h,
                                            open_mode = :m, backchannel_logout_uri = :b
                        WHERE key = :k
                        """)
                .param("n", displayName).param("d", description).param("u", url).param("c", accentColor)
                .param("l", accentLocked).param("h", healthUrl).param("m", openMode).param("b", backchannelLogoutUri)
                .param("k", key).update();
    }

    /** Vom Dienst gemeldete Farbe (null löscht sie). */
    public void setReportedColor(String key, String color) {
        jdbc.sql("UPDATE services SET reported_accent_color = :c WHERE key = :k").param("c", color).param("k", key).update();
    }

    private static ServiceInfo map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ServiceInfo(rs.getString("key"), rs.getString("display_name"), rs.getString("client_id"),
                rs.getString("required_permission"), rs.getString("open_mode"), rs.getString("health_url"),
                rs.getString("description"), rs.getString("url"), rs.getString("accent_color"),
                rs.getString("reported_accent_color"), rs.getBoolean("accent_locked"),
                rs.getString("backchannel_logout_uri"));
    }
}
