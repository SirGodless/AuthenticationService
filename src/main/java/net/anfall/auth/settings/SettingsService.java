package net.anfall.auth.settings;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Einfacher Schlüssel-Wert-Speicher für Einstellungen, die der Admin im Dashboard ändert. */
@Service
public class SettingsService {

    private final JdbcClient jdbc;

    public SettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM settings WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(Integer::parseInt).orElse(defaultValue);
    }

    public void put(String key, String value) {
        jdbc.sql("""
                        INSERT INTO settings (key, value) VALUES (:k, :v)
                        ON CONFLICT (key) DO UPDATE SET value = :v, updated_at = now()
                        """).param("k", key).param("v", value).update();
    }
}
