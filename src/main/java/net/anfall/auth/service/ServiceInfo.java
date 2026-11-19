package net.anfall.auth.service;

/**
 * Ein Dienst im Hub (z. B. Vaultwarden) samt dem Recht, das den Zugang regelt.
 * accentColor ist null oder "#rrggbb" (klein geschrieben) und im Hub eingestellt, reportedAccentColor die vom Dienst
 * gemeldete, url die Adresse zum Öffnen, backchannelLogoutUri die Adresse für das Logout-Token (null = keine Benachrichtigung).
 */
public record ServiceInfo(String key, String displayName, String clientId, String requiredPermission,
                          String openMode, String healthUrl, String description, String url, String accentColor,
                          String reportedAccentColor, boolean accentLocked, String backchannelLogoutUri) {

    /**
     * Die Farbe, die im Hub gilt: Meldet der Dienst eine, überschreibt sie die eingestellte, außer die Hub-Farbe ist
     * fest (accentLocked). Ohne beides gilt der allgemeine Akzent (null).
     */
    public String effectiveAccentColor() {
        return !accentLocked && reportedAccentColor != null ? reportedAccentColor : accentColor;
    }
}
