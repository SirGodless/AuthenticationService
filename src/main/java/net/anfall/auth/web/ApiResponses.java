package net.anfall.auth.web;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

/** Einheitliche Antworten der API. Antworten mit Geheimnissen oder Anmeldedaten dürfen nie zwischengespeichert werden. */
public final class ApiResponses {

    private ApiResponses() {
    }

    public static ResponseEntity<Map<String, Object>> ok() {
        return ok(Map.of("status", "ok"));
    }

    public static ResponseEntity<Map<String, Object>> valid() {
        return ok(Map.of("valid", true));
    }

    public static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
