package net.anfall.auth.service;

import java.util.List;

/** Ein Dienst für das Dashboard: die Anzeige-Eigenschaften plus die erlaubten Rücksprung-Adressen seines OIDC-Clients. */
public record ServiceAdminView(String key, String displayName, String clientId, String requiredPermission, String openMode,
                               String healthUrl, String description, String url, String accentColor,
                               String reportedAccentColor, boolean accentLocked, String backchannelLogoutUri,
                               List<String> redirectUris) {

    static ServiceAdminView of(ServiceInfo s, List<String> redirectUris) {
        return new ServiceAdminView(s.key(), s.displayName(), s.clientId(), s.requiredPermission(), s.openMode(), s.healthUrl(),
                s.description(), s.url(), s.accentColor(), s.reportedAccentColor(), s.accentLocked(), s.backchannelLogoutUri(), redirectUris);
    }
}
