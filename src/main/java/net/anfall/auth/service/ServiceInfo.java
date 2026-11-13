package net.anfall.auth.service;

/** Ein Dienst im Hub (z. B. Vaultwarden) samt dem Recht, das den Zugang regelt. */
public record ServiceInfo(String key, String displayName, String clientId, String requiredPermission,
                          String openMode, String healthUrl) {
}
