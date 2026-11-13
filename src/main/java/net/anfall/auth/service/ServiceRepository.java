package net.anfall.auth.service;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** SQL für die Tabelle services. */
@Component
public class ServiceRepository {

    private static final String COLUMNS = "key, display_name, client_id, required_permission, open_mode, health_url";

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
                        INSERT INTO services (key, display_name, client_id, required_permission, open_mode, health_url)
                        VALUES (:k, :n, :c, :p, :m, :h)
                        """)
                .param("k", s.key()).param("n", s.displayName()).param("c", s.clientId())
                .param("p", s.requiredPermission()).param("m", s.openMode()).param("h", s.healthUrl()).update();
    }

    private static ServiceInfo map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ServiceInfo(rs.getString("key"), rs.getString("display_name"), rs.getString("client_id"),
                rs.getString("required_permission"), rs.getString("open_mode"), rs.getString("health_url"));
    }
}
