package net.anfall.auth.invite;

import java.time.Duration;

/**
 * Grenzen für die Gültigkeit von Einladungslinks (CLAUDE.md Abschnitt 4). Im Dashboard einstellbar.
 * Ohne Rolle oder Rechte: Minimum, Standard, Maximum. Mit Rolle oder Rechten: eigene Höchstdauer, die zugleich der Standard ist.
 */
public record InviteLimits(int minHours, int defaultDays, int maxDays, int privilegedMaxDays) {

    /** Das Maximum von einem Monat ist fest und lässt sich nicht überschreiten. */
    public static final int HARD_MAX_DAYS = 30;

    public static final InviteLimits DEFAULTS = new InviteLimits(1, 7, HARD_MAX_DAYS, 3);

    public Duration standard(boolean privileged) {
        return Duration.ofDays(privileged ? privilegedMaxDays : defaultDays);
    }

    public Duration max(boolean privileged) {
        return Duration.ofDays(privileged ? privilegedMaxDays : maxDays);
    }

    public Duration min() {
        return Duration.ofHours(minHours);
    }
}
