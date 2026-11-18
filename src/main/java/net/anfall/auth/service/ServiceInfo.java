package net.anfall.auth.service;

/**
 * Ein Dienst im Hub (z. B. Vaultwarden) samt dem Recht, das den Zugang regelt.
 * accentColor ist null oder "#rrggbb" (klein geschrieben), url die Adresse zum Öffnen.
 */
public record ServiceInfo(String key, String displayName, String clientId, String requiredPermission,
                          String openMode, String healthUrl, String description, String url, String accentColor) {
}
